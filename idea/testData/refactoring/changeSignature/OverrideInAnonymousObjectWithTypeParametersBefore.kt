interface X

interface A<D> {
    public fun <caret>foo(receiverTypes: Collection<X>): Collection<D>
}

fun foo<D>(): A<D> {
    return object: A<D> {
        override fun foo(receiverTypes: Collection<X>): Collection<D> {
            throw UnsupportedOperationException()
        }
    }
}