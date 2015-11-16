enum class E1 {
    ENTRY;

    <!ACCIDENTAL_OVERRIDE!>fun name(): String<!> = "lol"
    <!ACCIDENTAL_OVERRIDE!>fun ordinal(): Int<!> = 0
}

enum class E2 {
    ENTRY;

    fun name(): Double = 3.0
    fun ordinal(): String = "!"
}

enum class E3 {
    ENTRY;

    fun name(<!UNUSED_PARAMETER!>x<!>: Int = 0): String = ""
    fun ordinal(<!UNUSED_PARAMETER!>x<!>: Int = 0): Int = 0
}


// KT-9640 Exception java.lang.VerifyError: class Bar overrides final method name.()Ljava/lang/String;
interface Foo {
    fun name(): String
    fun ordinal(): Int
}

enum class <!CONFLICTING_INHERITED_JVM_DECLARATIONS!>Bar<!> : Foo {
    <!ABSTRACT_MEMBER_NOT_IMPLEMENTED!>one<!>, <!ABSTRACT_MEMBER_NOT_IMPLEMENTED!>two<!>;

    <!ACCIDENTAL_OVERRIDE!>override fun name()<!> = name
}

enum class A {
    X {
        <!ACCIDENTAL_OVERRIDE!>fun name()<!> = ""
    },
    Y {
        <!ACCIDENTAL_OVERRIDE!>fun ordinal()<!> = 100
    }
}

