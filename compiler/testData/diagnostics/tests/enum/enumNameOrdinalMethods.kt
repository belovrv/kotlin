enum class E1 {
    ENTRY;

    <!ENUM_NAME_ORDINAL_CLASH!>fun name(): String = "lol"<!>
    <!ENUM_NAME_ORDINAL_CLASH!>fun ordinal(): Int = 0<!>
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

enum class Bar : Foo {
    <!ABSTRACT_MEMBER_NOT_IMPLEMENTED!>one<!>, <!ABSTRACT_MEMBER_NOT_IMPLEMENTED!>two<!>;

    <!ENUM_NAME_ORDINAL_CLASH!>override fun name() = name<!>
}
