package com.stzb.server.protocol

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** 形状对照断言：只校验 JSON 结构(顶层类型/元组长度/对象键集)，不校验数值。 */
object ShapeAssert {
    private val mapper = jacksonObjectMapper()

    fun topLevelKind(json: String): String {
        val node = mapper.readTree(json)
        return when {
            node.isArray -> "array"
            node.isObject -> "object"
            node.isBoolean -> "boolean"
            node.isNumber -> "number"
            node.isNull -> "null"
            else -> "string"
        }
    }

    fun tupleSize(json: String): Int {
        val node = mapper.readTree(json)
        require(node.isArray) { "not an array: $json" }
        return node.size()
    }

    fun assertSameShape(expected: String, actual: String) {
        val ek = topLevelKind(expected)
        val ak = topLevelKind(actual)
        if (ek != ak) throw AssertionError("top-level kind mismatch: expected=$ek actual=$ak")
        val en: JsonNode = mapper.readTree(expected)
        val an: JsonNode = mapper.readTree(actual)
        if (ek == "array" && en.size() != an.size()) {
            throw AssertionError("array length mismatch: expected=${en.size()} actual=${an.size()}")
        }
        if (ek == "object") {
            val ekeys = en.fieldNames().asSequence().toSet()
            val akeys = an.fieldNames().asSequence().toSet()
            if (ekeys != akeys) throw AssertionError("object keys mismatch: expected=$ekeys actual=$akeys")
        }
    }
}
