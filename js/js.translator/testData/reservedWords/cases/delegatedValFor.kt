package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

interface Trait {
    val `for`: Int
}

class TraitImpl : Trait {
    override val `for`: Int = 0
}

class TestDelegate : Trait by TraitImpl() {
    fun test() {
        testNotRenamed("for", { `for` })
    }
}

fun box(): String {
    TestDelegate().test()

    return "OK"
}