package lt.nook.app.auth

class InMemoryTokenStore(private var t: String? = null) : TokenStore {
    override fun get() = t
    override fun set(token: String) { t = token }
    override fun clear() { t = null }
}
