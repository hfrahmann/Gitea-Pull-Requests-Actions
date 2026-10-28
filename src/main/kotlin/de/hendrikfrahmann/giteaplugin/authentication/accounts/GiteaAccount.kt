package de.hendrikfrahmann.giteaplugin.authentication.accounts

import com.intellij.collaboration.auth.Account
import com.intellij.collaboration.auth.ServerAccount
import com.intellij.openapi.util.NlsSafe
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Property
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.Transient
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath

/** A Gitea login: user [name] on [server]. Credentials live in PasswordSafe under [id]; equality is by [id]. */
@Tag("account")
class GiteaAccount() : ServerAccount() {
  @field:Attribute("id")
  override var id: String = Account.generateId()
    private set

  @field:Attribute("name")
  override var name: @NlsSafe String = ""
    private set

  @field:Property(surroundWithTag = false)
  override var server: GiteaServerPath = GiteaServerPath()
    private set

  constructor(name: String, server: GiteaServerPath, id: String = Account.generateId()) : this() {
    this.id = id
    this.name = name
    this.server = server
  }

  /** The stand-in of [anonymous] connections; never stored in the account manager. */
  @get:Transient
  val isAnonymous: Boolean get() = id.startsWith(ANONYMOUS_ID_PREFIX)

  @NlsSafe
  override fun toString(): String = "$server/$name"

  companion object {
    private const val ANONYMOUS_ID_PREFIX = "anonymous@"

    /** No login on [server]: read-only access to public repositories. Equal for the same server. */
    fun anonymous(server: GiteaServerPath): GiteaAccount = GiteaAccount("", server, ANONYMOUS_ID_PREFIX + server)

    fun isAnonymousId(id: String?): Boolean = id?.startsWith(ANONYMOUS_ID_PREFIX) == true
  }
}
