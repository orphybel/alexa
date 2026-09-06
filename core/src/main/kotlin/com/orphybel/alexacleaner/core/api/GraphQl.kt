package com.orphybel.alexacleaner.core.api

/**
 * Minimal GraphQL selection tree, so a query can be trimmed automatically when Amazon's
 * schema rejects a field we asked for (the Nexus schema is undocumented and changes).
 */
class GqlField(val name: String, children: List<GqlField> = emptyList(), val args: String = "") {
    val children: MutableList<GqlField> = children.toMutableList()

    fun copy(): GqlField = GqlField(name, children.map { it.copy() }, args)

    fun render(): String = buildString {
        append(name)
        if (args.isNotBlank()) append("(").append(args).append(")")
        if (children.isNotEmpty()) {
            append(" { ")
            append(children.joinToString(" ") { it.render() })
            append(" }")
        }
    }

    fun find(path: List<String>): GqlField? {
        if (path.isEmpty()) return this
        if (path.first() != name) return null
        if (path.size == 1) return this
        return children.firstNotNullOfOrNull { it.find(path.drop(1)) }
    }

    /** Removes the node at [path] (relative to this node, whose name is the first element). Returns true when found. */
    fun removePath(path: List<String>): Boolean {
        if (path.size < 2 || path.first() != name) return false
        if (path.size == 2) return children.removeIf { it.name == path[1] }
        return children.any { it.removePath(path.drop(1)) }
    }

    /** Removes every descendant named [fieldName]. Returns the number removed. */
    fun removeAllNamed(fieldName: String): Int {
        var removed = 0
        val it = children.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (c.name == fieldName) { it.remove(); removed++ } else removed += c.removeAllNamed(fieldName)
        }
        return removed
    }

    /** Drops the sub-selection of every descendant named [fieldName] (it turned out to be a scalar). */
    fun flattenAllNamed(fieldName: String): Int {
        var n = 0
        for (c in children) {
            if (c.name == fieldName && c.children.isNotEmpty()) { c.children.clear(); n++ } else n += c.flattenAllNamed(fieldName)
        }
        return n
    }

    fun leafCount(): Int = if (children.isEmpty()) 1 else children.sumOf { it.leafCount() }

    companion object {
        fun f(name: String, vararg children: GqlField): GqlField = GqlField(name, children.toList())
    }
}

/** A validation error reported by a GraphQL server, reduced to what we need to repair the query. */
data class GqlValidationError(val kind: Kind, val path: List<String>?, val fieldName: String?) {
    enum class Kind { UNDEFINED_FIELD, SUBSELECTION_REQUIRED, SUBSELECTION_NOT_ALLOWED, OTHER }

    companion object {
        private val PATH_BRACKET = Regex("@\\[([^\\]]+)\\]")
        private val PATH_QUOTE = Regex("@ '([^']+)'")
        private val FIELD_SINGLE = Regex("[Ff]ield '([^']+)'")
        private val FIELD_DOUBLE = Regex("[Ff]ield \"([^\"]+)\"")

        fun parse(message: String): GqlValidationError {
            val kind = when {
                message.contains("FieldUndefined") || message.contains("Cannot query field") || message.contains("is undefined") -> Kind.UNDEFINED_FIELD
                message.contains("SubselectionNotAllowed", true) || message.contains("must not have a selection") || message.contains("not allowed on leaf") -> Kind.SUBSELECTION_NOT_ALLOWED
                message.contains("SubSelectionRequired", true) || message.contains("must have a selection of subfields") || message.contains("Subselection required") -> Kind.SUBSELECTION_REQUIRED
                else -> Kind.OTHER
            }
            val path = (PATH_BRACKET.find(message) ?: PATH_QUOTE.find(message))?.groupValues?.get(1)?.split('/')?.filter { it.isNotBlank() }
            val field = (FIELD_SINGLE.find(message) ?: FIELD_DOUBLE.find(message))?.groupValues?.get(1) ?: path?.lastOrNull()
            return GqlValidationError(kind, path, field)
        }
    }
}

/** The smart-home endpoint listing used by the Alexa app since the retirement of `GET /api/phoenix`. */
object SmartHomeQuery {
    const val OPERATION = "CustomerSmartHome"
    const val ROOT = "endpoints"
    const val ROOT_ARGS = "endpointsQueryParams: { paginationParams: { disablePagination: true } }"

    /** Every field we would like; the client trims what the server rejects. */
    fun full(): GqlField = GqlField(
        ROOT,
        listOf(
            GqlField.f(
                "items",
                GqlField.f("endpointId"),
                GqlField.f("id"),
                GqlField.f("friendlyName"),
                GqlField.f("enablement"),
                GqlField.f("displayCategories", GqlField.f("primary", GqlField.f("value")), GqlField.f("all", GqlField.f("value"))),
                GqlField.f(
                    "legacyIdentifiers",
                    GqlField.f("chrsIdentifier", GqlField.f("entityId")),
                    GqlField.f(
                        "dmsIdentifier",
                        GqlField.f("deviceType", GqlField.f("type", GqlField.f("text"))),
                        GqlField.f("deviceSerialNumber", GqlField.f("type", GqlField.f("text"))),
                    ),
                ),
                GqlField.f(
                    "legacyAppliance",
                    GqlField.f("applianceId"),
                    GqlField.f("applianceKey"),
                    GqlField.f("applianceTypes"),
                    GqlField.f("mergedApplianceIds"),
                    GqlField.f("connectedVia"),
                    GqlField.f("modelName"),
                    GqlField.f("friendlyDescription"),
                    GqlField.f("version"),
                    GqlField.f("friendlyName"),
                    GqlField.f("manufacturerName"),
                    GqlField.f("isEnabled"),
                    GqlField.f("entityId"),
                    GqlField.f("driverIdentity", GqlField.f("namespace"), GqlField.f("identifier")),
                    GqlField.f("applianceNetworkState", GqlField.f("reachability"), GqlField.f("lastSeenAt"), GqlField.f("createdAt")),
                    GqlField.f("capabilities"),
                    GqlField.f("aliases", GqlField.f("friendlyName")),
                ),
            ),
        ),
        ROOT_ARGS,
    )

    /** The small field set known to work with alexa-remote-control / alexa-remote2. */
    fun minimal(): GqlField = GqlField(
        ROOT,
        listOf(
            GqlField.f(
                "items",
                GqlField.f("endpointId"),
                GqlField.f("id"),
                GqlField.f("friendlyName"),
                GqlField.f("displayCategories", GqlField.f("primary", GqlField.f("value"))),
                GqlField.f("legacyIdentifiers", GqlField.f("chrsIdentifier", GqlField.f("entityId"))),
                GqlField.f(
                    "legacyAppliance",
                    GqlField.f("applianceId"),
                    GqlField.f("mergedApplianceIds"),
                    GqlField.f("connectedVia"),
                    GqlField.f("applianceKey"),
                    GqlField.f("modelName"),
                    GqlField.f("friendlyDescription"),
                    GqlField.f("version"),
                    GqlField.f("friendlyName"),
                    GqlField.f("manufacturerName"),
                ),
            ),
        ),
        ROOT_ARGS,
    )

    fun render(root: GqlField): String = "query $OPERATION { ${root.render()} }"

    /**
     * Applies one validation error to [root]. Returns true when the query changed (so it is
     * worth retrying), false when the error cannot be repaired.
     */
    fun repair(root: GqlField, error: GqlValidationError): Boolean {
        val path = error.path
        val name = error.fieldName ?: return false
        when (error.kind) {
            GqlValidationError.Kind.SUBSELECTION_NOT_ALLOWED -> {
                if (path != null) root.find(path)?.let { it.children.clear(); return true }
                return root.flattenAllNamed(name) > 0
            }
            GqlValidationError.Kind.UNDEFINED_FIELD, GqlValidationError.Kind.SUBSELECTION_REQUIRED -> {
                if (path != null && path.size >= 2 && root.removePath(path)) return true
                return root.removeAllNamed(name) > 0
            }
            GqlValidationError.Kind.OTHER -> return false
        }
    }
}
