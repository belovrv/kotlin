package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

interface Trait {
    val `if`: Int
}

class TraitImpl : Trait {
    override val `if`: Int = 0
}

class TestDelegate : Trait by TraitImpl() {
    fun test() {
        testNotRenamed("if", { `if` })
    }
}

fun box(): String {
    TestDelegate().test()

    return "OK"
}