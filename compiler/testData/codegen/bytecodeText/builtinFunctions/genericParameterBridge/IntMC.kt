abstract class A7 : MutableCollection<Int> {
    override fun contains(o: Int): Boolean {
        throw UnsupportedOperationException()
    }
}

// 1 public final bridge contains\(Ljava/lang/Object;\)Z
// 1 public final bridge remove\(Ljava/lang/Object;\)Z
