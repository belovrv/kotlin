abstract class A<T : Any> : MutableCollection<T> {
    override fun contains(o: T): Boolean {
        throw UnsupportedOperationException()
    }
}

// 1 bridge
// 1 public final bridge size
// 0 INSTANCEOF
// 1 IFNONNULL
