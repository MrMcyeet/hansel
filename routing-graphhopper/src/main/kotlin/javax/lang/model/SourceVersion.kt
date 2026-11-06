@file:Suppress("PackageDirectoryMismatch", "unused")

package javax.lang.model

/**
 * Android shim for the JDK's `javax.lang.model.SourceVersion` (module `java.compiler`), which ART does not ship.
 *
 * GraphHopper 11.1 calls `SourceVersion.isKeyword(CharSequence)` from `IntEncodedValueImpl.isValidEncodedValue`
 * (i.e. from the constructor of **every** encoded value, including when a graph is loaded from `properties`) and
 * from `JsonFeature.isValidId` (custom-model areas). Without this class the first `EncodingManager` creation on a
 * phone dies with `NoClassDefFoundError`.
 *
 * On the JVM (unit tests, dev machine) the bootstrap class loader's real `java.compiler` version wins and this file
 * is never loaded; in the APK D8 packages it like any other `javax.*` library class (only `java.*` is prohibited).
 * Only the members GraphHopper uses are provided.
 */
class SourceVersion private constructor() {
    companion object {
        private val KEYWORDS: Set<String> = hashSetOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "_",
            // literals, treated as keywords by the JDK implementation
            "true", "false", "null",
        )

        /** Same contract as the JDK: true for Java keywords and the literals `true`, `false`, `null`. */
        @JvmStatic
        fun isKeyword(s: CharSequence): Boolean = s.toString() in KEYWORDS

        /** `isName` as the JDK defines it: a dotted sequence of identifiers, none of which is a keyword. */
        @JvmStatic
        fun isName(name: CharSequence): Boolean {
            val s = name.toString()
            if (s.isEmpty()) return false
            return s.split('.').all { isIdentifier(it) && !isKeyword(it) }
        }

        @JvmStatic
        fun isIdentifier(name: CharSequence): Boolean {
            val s = name.toString()
            if (s.isEmpty() || !Character.isJavaIdentifierStart(s[0])) return false
            for (i in 1 until s.length) if (!Character.isJavaIdentifierPart(s[i])) return false
            return true
        }
    }
}
