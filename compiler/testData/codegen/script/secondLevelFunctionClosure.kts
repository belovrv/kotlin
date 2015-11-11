val z = 30
val x: Int

if (true) {
    fun foo() = z + 20
    x = foo()
}

val rv = x

// expected: rv: 50
