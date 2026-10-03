package net.jamesjennison.filamajignfc.core

sealed interface JsonValue {
    data class Obj(val values: LinkedHashMap<String, JsonValue>) : JsonValue
    data class Arr(val values: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val lexical: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

class StrictJson(private val maxBytes: Int = 16_384, private val maxDepth: Int = 16) {
    fun parse(bytes: ByteArray): JsonValue {
        if (bytes.size > maxBytes) throw ValidationException("JSON exceeds $maxBytes bytes")
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) throw ValidationException("Invalid UTF-8")
        val parser = Parser(text, maxDepth)
        return parser.parse()
    }

    private class Parser(private val s: String, private val depthLimit: Int) {
        private var i = 0
        fun parse(): JsonValue { val v = value(0); ws(); if (i != s.length) fail("Trailing data"); return v }
        private fun value(depth: Int): JsonValue {
            if (depth > depthLimit) fail("Nesting limit exceeded")
            ws(); if (i >= s.length) fail("Unexpected end")
            return when (s[i]) {
                '{' -> obj(depth + 1); '[' -> arr(depth + 1); '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true)); 'f' -> literal("false", JsonValue.Bool(false)); 'n' -> literal("null", JsonValue.Null)
                '-', in '0'..'9' -> number(); else -> fail("Unexpected character")
            }
        }
        private fun obj(depth: Int): JsonValue.Obj { i++; ws(); val out = linkedMapOf<String, JsonValue>(); if (take('}')) return JsonValue.Obj(out); while (true) { ws(); if (i >= s.length || s[i] != '"') fail("Object key required"); val key = string(); if (out.containsKey(key)) fail("Duplicate key: $key"); ws(); expect(':'); out[key] = value(depth); ws(); if (take('}')) return JsonValue.Obj(out); expect(',') } }
        private fun arr(depth: Int): JsonValue.Arr { i++; ws(); val out = mutableListOf<JsonValue>(); if (take(']')) return JsonValue.Arr(out); while (true) { out += value(depth); ws(); if (take(']')) return JsonValue.Arr(out); expect(',') } }
        private fun string(): String {
            expect('"'); val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("Bad escape")
                        when (val e=s[i++]) {
                            '"','\\','/' -> out.append(e); 'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                val first=unicodeEscape()
                                if (first.isHighSurrogate()) {
                                    if (i+2>s.length || s[i]!='\\' || s[i+1]!='u') fail("High surrogate must be paired")
                                    i+=2; val second=unicodeEscape(); if(!second.isLowSurrogate()) fail("High surrogate must be paired")
                                    out.append(first).append(second)
                                } else if(first.isLowSurrogate()) fail("Lone low surrogate") else out.append(first)
                            }
                            else -> fail("Bad escape")
                        }
                    }
                    c.code < 0x20 -> fail("Control character")
                    c.isHighSurrogate() -> {
                        if (i >= s.length || !s[i].isLowSurrogate()) fail("Unpaired surrogate")
                        out.append(c).append(s[i++])
                    }
                    c.isLowSurrogate() -> fail("Unpaired surrogate")
                    else -> out.append(c)
                }
            }
            fail("Unterminated string")
        }
        private fun unicodeEscape():Char { if(i+4>s.length) fail("Bad unicode escape"); val hex=s.substring(i,i+4); val value=hex.toIntOrNull(16)?.toChar() ?: fail("Bad unicode escape"); i+=4; return value }
        private fun number(): JsonValue.Num { val start=i; if(take('-') && i>=s.length) fail("Bad number"); if(take('0')) { if(i<s.length && s[i] in '0'..'9') fail("Leading zero") } else { digits() }; if(take('.')) digits(); if(i<s.length && (s[i]=='e'||s[i]=='E')) { i++; if(i<s.length&&(s[i]=='+'||s[i]=='-')) i++; digits() }; if(i-start>128) fail("Number is too long"); return JsonValue.Num(s.substring(start,i)) }
        private fun digits() { val start=i; while(i<s.length&&s[i] in '0'..'9') i++; if(i==start) fail("Digit required") }
        private fun <T:JsonValue> literal(word:String,v:T):T { if(!s.startsWith(word,i)) fail("Bad literal"); i+=word.length; return v }
        private fun ws(){ while(i<s.length&&s[i] in " \n\r\t") i++ }
        private fun take(c:Char)=if(i<s.length&&s[i]==c){i++;true}else false
        private fun expect(c:Char){ if(!take(c)) fail("Expected $c") }
        private fun fail(message:String):Nothing=throw ValidationException("$message at offset $i")
    }
}

fun JsonValue.Obj.string(key: String): String? = (values[key] as? JsonValue.Str)?.value
fun JsonValue.Obj.int(key: String): Int? = (values[key] as? JsonValue.Num)?.lexical?.toBigDecimalOrNull()?.let { runCatching { it.intValueExact() }.getOrNull() }
fun JsonValue.Obj.decimal(key: String): String? = (values[key] as? JsonValue.Num)?.lexical

fun encodeJsonObject(values: Map<String, Any>): ByteArray {
    fun quote(s: String) = buildString { append('"'); s.forEach { c -> when(c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if(c.code<32) append("\\u%04x".format(c.code)) else append(c) } }; append('"') }
    val text = values.entries.joinToString(prefix="{", postfix="}", separator=",") { (k,v) -> quote(k)+":"+when(v){ is String -> quote(v); is Number, is Boolean -> v.toString(); is List<*> -> v.joinToString(prefix="[",postfix="]") { quote(it.toString()) }; else -> error("Unsupported JSON value") } }
    return text.toByteArray(Charsets.UTF_8)
}
