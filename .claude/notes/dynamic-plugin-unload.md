# Plugin ohne Neustart entladen – Erkenntnisse (2026-11-07)

Stand des Repos: PR #27 (`claude/restart-only-on-unload`) installiert ohne Neustart und **verhindert das Entladen immer**
(`GiteaUnloadVetoer`, `ide.dynamicPluginVetoer`). Dieses Dokument hält fest, was nötig wäre, damit auch Deaktivieren,
Deinstallieren und Updates ohne Neustart gehen. Der Code dafür wurde getestet und danach wieder zurückgebaut.

## Kurzfassung

- Entladen scheitert schon **ohne jede Nutzung** des Plugins, an Lecks in der Plattform (2026.1–2026.3).
- Mit fünf Workarounds in einem `DynamicPluginListener.beforePluginUnload` und zwei Vetos entlädt das Plugin in allen
  getesteten Fällen sauber, außer einem seltenen Leck in 2026.1 (Projektbaum, siehe unten).
- Zwei Fälle sind nicht sinnvoll lösbar und brauchen weiterhin einen Neustart: **PR-Tabs waren offen** und
  **Dateien unter `.gitea/` sind im Editor offen**.
- 2026.2 und 2026.3 entladen über eine neue Engine, die **gar nicht mehr prüft**, ob der ClassLoader freigegeben wird.
  Ein Leck fällt dort niemandem auf, es bleibt still im Speicher.

## Ergebnisse

Szenarien: **A** IDE starten, nichts benutzen · **B** beide Tool Windows öffnen (PR-Liste geladen) · **C** PR-Timeline
und PR-Diff im Editor öffnen und wieder schließen · **D** `.gitea/workflows/ci.yml` im Editor offen · **D2** wie D, Datei
vor dem Entladen geschlossen.

Ohne Workarounds (Stand PR #27 ohne Veto):

| Szenario | 2026.1 |
|---|---|
| A | Leck: Stripe-Button, Icon-Cache |
| B | Leck: zusätzlich Islands-UI |

Mit allen Workarounds und Vetos:

| Szenario | 2026.1 | 2026.2 | 2026.3 |
|---|---|---|---|
| A | meist sauber, 1× Projektbaum-Leck | sauber | sauber |
| B | sauber | sauber | sauber |
| C | Veto (sonst Leck) | Veto (sonst Leck) | Veto (sonst Leck) |
| D | Veto (sonst Leck) | Veto | Veto (sonst Leck) |
| D2 | sauber | sauber | sauber, 1× nur kurzzeitig gehalten |

„Sauber“ heißt: ClassLoader nach dem Entladen per GC freigegeben, in 2026.1 zusätzlich „Successfully unloaded plugin“
im Log.

## Die Lecks im Einzelnen

Pfade aus der Heap-Analyse der Plattform (`AnalyzeClassloaderReferencesGraph`), jeweils bis zum `PluginClassLoader`.

### 1. Stripe-Button bleibt stehen (2026.1–2026.3, auch master)

`ToolWindowLeftToolbar.topStripe.buttons` → `ToolWindowImpl` → `stripeTitleProvider` → `PluginClassLoader`

`ToolWindowManagerImpl.doUnregisterToolWindow` entfernt Decorator und Stripe-Button nur, wenn `layoutState.getInfo(id)`
nicht `null` ist. Der Code nimmt an, ohne Layout-Eintrag sei nie ein Button angezeigt worden. Im neuen UI stimmt das
nicht: Ein Plugin-Tool-Window, das nie verschoben oder geöffnet wurde, hat einen Button, aber keinen Eintrag.

Workaround: eine unveränderte Eigenschaft setzen, das legt den Eintrag über `getRegisteredMutableInfoOrLogError` an:
`toolWindow.isAutoHide = toolWindow.isAutoHide`.

### 2. Islands-UI hält ein angezeigtes Tool Window (2026.1, 2026.3)

`ContentManagerImpl$MyNonOpaquePanel` → `XNextIslandHolder` → `borderPainter` (`IslandsUICustomization`) →
`ToolWindowImpl`

Workaround: `toolWindow.contentManager.removeAllContents(true)` und danach `toolWindow.hide()` (2026.3 braucht beides).
**Reihenfolge wichtig:** zuerst die Inhalte entfernen. Beim Verstecken mit Inhalten ändern sich deren Tab-Titel, und
`ContentTabLabel` plant per `EdtScheduler.schedule(50)` ein `revalidate()` ein, das selbst das Tool Window hält.
Nebenwirkung von `hide()`: Nach einem Update ist das Tool Window geschlossen.

### 3. Cache für ausgegraute Icons (2026.1)

`IconUtilKt.iconToDisabledIcon` (Caffeine) → `CachedImageIcon` (`icons/giteaToolWindow.svg`) → `ImageDataByPathLoader`

Beim Entladen ruft die Plattform nur `IconLoader.detachClassLoader()` auf, das diesen Cache nicht leert.
Workaround: `IconLoader.clearCache()` (öffentlich in 2026.1–2026.3), **synchron** in `beforePluginUnload`. Ein
`invokeLater` kommt zu spät: Das Entladen läuft unter einem modalen Fortschritt.

### 4. Coroutines der Tool-Window-Inhalte (2026.1)

`EventDispatchThread` → `NonBlockingFlushQueue.skippedWriteIntentQueue` → `DispatchedRunnable` → Completion-Handler
eines Jobs mit `cancelOnDispose(toolWindow.contentManager)`

Die Jobs aus `manageContent` werden erst abgebrochen, wenn das Tool Window beim Entladen abgemeldet wird. Ihr Abschluss
wartet dann auf dem EDT, der während der GC-Prüfung blockiert ist. Zusätzlich hält `cancelOnDispose` einen
`Throwable` mit Stacktrace, der auf Plugin-Klassen zeigt.
Workaround: In beiden Controllern (`GiteaPullRequestsToolWindowController`, `GiteaActionsToolWindowController`) ein
`fun cancelContent() { cs.cancel() }` und es in `beforePluginUnload` aufrufen. Direkt danach ruft die Plattform
`IdeEventQueue.flushQueue()` auf, so ist alles erledigt, bevor geprüft wird.

### 5. PR-Dateien (alle Versionen) – Veto

`EditorsSplitters` → `EditorWindow.removedTabs` („Reopen Closed Tab“) → `GiteaPullRequestTimelineFile`
Außerdem ruft `BackendRecentFileEventsModel` nach dem Abmelden des `giteapr`-Dateisystems noch `getPath()` auf
(SEVERE: „null cannot be cast to … GiteaVirtualFileSystem“).

Nicht über öffentliche API lösbar. Veto: Markierung setzen, sobald `GiteaVirtualFileSystem.findOrCreateFile` eine
Datei erzeugt (öffnen oder beim Start wiederherstellen). Die Markierung gilt bis zum Neustart.

### 6. Dateien unter `.gitea/` im Editor (2026.1, 2026.3) – Veto

- 2026.1: `EditorTabs.selectedInfo.iconHolder` (`SpinnerTabIconHolder`) → Icon aus `GiteaWorkflowFileIconProvider`
- 2026.1 und 2026.3: Navigationsleiste in der Statuszeile `NavBarItemComponent` → `DeferredIconImpl` → Icon aus
  `GiteaDirectoryIconProvider`

Tab-Icons in 2026.1 werden beim Entladen nicht neu berechnet, die Navigationsleiste ist ein Wettlauf. Veto, solange in
irgendeinem offenen Projekt eine Datei mit `/.gitea/` im Pfad offen ist. Nach dem Schließen klappt das Entladen (D2).

### 7. Projektbaum (2026.1, selten) – offen

`ProjectViewTree` → `AsyncTreeModel` → `PsiDirectoryNode` (`.gitea`) → `myClosedIcon` aus `GiteaDirectoryIconProvider`

Tritt nur manchmal auf, je nachdem wie schnell der Baum nach dem Entladen neu zeichnet. Das Plugin ist dann schon
entladen, die IDE meldet aber „Neustart nötig“. Möglicher Ansatz, nicht getestet: Icons, die außerhalb der eigenen UI
angezeigt werden (Tool-Window-, Ordner-, Workflow-Icon), per URL statt über den Plugin-ClassLoader laden. Dann kann
kein Cache den ClassLoader halten. Offen ist, ob Dark- und New-UI-Varianten dann noch richtig aufgelöst werden.

## Code, der funktioniert hat

`plugin.xml`:

```xml
<applicationListeners>
  <listener class="de.hendrikfrahmann.giteaplugin.GiteaUnloadPreparer" topic="com.intellij.ide.plugins.DynamicPluginListener"/>
</applicationListeners>
<extensions defaultExtensionNs="com.intellij">
  <ide.dynamicPluginVetoer implementation="de.hendrikfrahmann.giteaplugin.GiteaUnloadVetoer"/>
</extensions>
```

`GiteaUnloadPreparer.beforePluginUnload` (nur für die eigene Plugin-ID):

```kotlin
for (project in ProjectManager.getInstance().openProjects) {
  project.serviceIfCreated<GiteaPullRequestsToolWindowController>()?.cancelContent()
  project.serviceIfCreated<GiteaActionsToolWindowController>()?.cancelContent()
  val toolWindowManager = ToolWindowManager.getInstance(project)
  for (id in listOf(GiteaToolWindowFactory.ID, GiteaActionsToolWindowFactory.ID)) {
    val toolWindow = toolWindowManager.getToolWindow(id) ?: continue
    toolWindow.isAutoHide = toolWindow.isAutoHide          // Layout-Eintrag anlegen (Leck 1)
    toolWindow.contentManager.removeAllContents(true)      // Leck 2, vor hide()
    toolWindow.hide()
  }
}
IconLoader.clearCache()                                    // Leck 3
```

`GiteaUnloadVetoer.vetoPluginUnload`:

```kotlin
when {
  pluginDescriptor.pluginId != PLUGIN_ID -> null
  pullRequestFilesCreated.get() -> GiteaBundle.message("plugin.unload.restart.required")
  giteaFileOpen() -> GiteaBundle.message("plugin.unload.restart.required.gitea.file")
  else -> null
}
// giteaFileOpen: ProjectManager.openProjects.any { !it.isDisposed && FileEditorManager.getInstance(it).openFiles.any { "/.gitea/" in it.path } }
// pullRequestFilesCreated: AtomicBoolean, gesetzt in GiteaVirtualFileSystem.findOrCreateFile
```

Texte:

```properties
plugin.unload.restart.required=Pull requests were opened in editor tabs. Gitea Pull Requests & Actions needs an IDE restart to be unloaded.
plugin.unload.restart.required.gitea.file=Files from a .gitea folder are open in the editor. Close them, or restart the IDE, to unload Gitea Pull Requests & Actions.
```

## Wie getestet wurde

- Lokaler Gitea-Stack, öffentliches Repo `johndoe/Demo-Project` (3 PRs), anonym verbunden, also ohne Token.
- IDE gestartet **direkt per `java`** mit Klassenpfad und JVM-Argumenten aus `Resources/product-info.json` der
  Distribution im Gradle-Cache, dazu eigenen `idea.config/system/plugins/log.path` im Scratchpad und
  `-Didea.paths.selector=<eigener Name>`. Vor und nach dem Start mit `lsof -p <pid>` geprüft, dass nichts unter
  `~/Library/…/JetBrains/IntelliJIdea*` offen ist.
- Plugin und `robot-server-plugin` aus dem von `prepareSandbox_<task>` vorbereiteten Verzeichnis kopiert, Steuerung per
  Remote Robot (`POST /js/execute`, Rhino-JS).
- Leck-Diagnose: `-Dide.plugins.snapshot.on.unload.fail=true -Dide.plugins.analyze.snapshot=true` (nur 2026.1 wertet
  selbst aus). Für 2026.2/2026.3 eigene Prüfung: `WeakReference` auf den ClassLoader vor dem Entladen, danach mehrfach
  `System.gc()`, bei Leck `MemoryDumpHelper.captureMemoryDump` und `HProfAnalysis` + `AnalyzeClassloaderReferencesGraph`.

### Stolperfallen beim Testen

- **Entladen aus dem richtigen Kontext auslösen:** `ApplicationManager.invokeLater(…, ModalityState.nonModal())`. Direkt
  aus einem Robot-Skript auf dem EDT gibt es „Write-unsafe context“ (`TransactionGuardImpl`), und als Folge wechselt
  `EditorColorsManager` das Farbschema samt `updateUI()`. Das verfälscht die Ergebnisse.
- **Gradle-`runIde` taugt für 2026.2+ nicht:** Es startet mit `-Dplugin.path` und `-Didea.required.plugins.id=<Plugin>`.
  Die neue Engine nimmt ein Pflicht-Plugin nie heraus („was expected not to be loaded but was included in the module
  graph“). Die beiden Argumente kommen aus demselben Argument-Provider wie die Sandbox-Pfade. Wer den Provider entfernt,
  startet die IDE mit den **echten** Benutzerverzeichnissen (passiert, siehe unten).
- **API-Unterschiede:**
  - 2026.1: `DynamicPlugins.unloadPlugin(d, UnloadPluginOptions(...))`, die Vetoer fragt nur
    `checkCanUnloadWithoutRestart`, also vorher aufrufen.
  - 2026.2: `unloadPlugin` mit Optionen existiert, ignoriert sie aber in der neuen Engine; `checkCanUnloadWithoutRestart`
    nur im Hintergrund-Thread. Vorher `PluginEnabler.HEADLESS.disable(...)`.
  - 2026.3: `UnloadPluginOptions` entfernt, nur noch `unloadPlugin(descriptor)`.
- Der macOS-Launcher (`MacOS/idea` mit `IDEA_PROPERTIES`/`IDEA_VM_OPTIONS`) startete aus dem Gradle-Cache gar nicht,
  ohne Fehlermeldung.

### Vorfall

Ein Testlauf von 2026.2 lief am 2026-11-07 von etwa 16:45 bis 16:55 mit den echten Verzeichnissen `IntelliJIdea2026.2`.
Die dabei angelegten Spuren des Demo-Projekts (Caches, Logs, `tasks/demo.*`) wurden gelöscht. Geändert blieben
`options/ide.general.xml`, `options/jdk.table.xml`, `idea.key`, `early-access-registry.txt`, `app-internal-state.db`,
`updatedBrokenPlugins.db` und `splash-subscription-mode.txt`.
