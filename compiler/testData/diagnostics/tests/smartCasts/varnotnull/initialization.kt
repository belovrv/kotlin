fun foo() {
    var v: String? = "xyz"
    // It is possible in principle to provide smart cast here
    v<!UNSAFE_CALL!>.<!>length
    v = null
    <!ALWAYS_NULL!>v<!><!UNSAFE_CALL!>.<!>length
}