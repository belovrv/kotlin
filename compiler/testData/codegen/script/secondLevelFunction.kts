val x: Int

if (true) {
    fun foo(y: Int) = y + 20
    x = foo(9)
}

val rv = x

// expected: rv: 29
