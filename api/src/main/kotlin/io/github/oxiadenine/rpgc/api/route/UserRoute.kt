package io.github.oxiadenine.rpgc.api.route

import io.github.oxiadenine.rpgc.common.repository.User
import io.github.oxiadenine.rpgc.common.repository.UserRepository
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.lang.NumberFormatException

@Serializable
data class UsersPost(val users: JsonArray)

@Serializable
data class UsersDelete(val users: JsonArray)

fun RequestValidationConfig.validateUsersPost() = validate<UsersPost> { usersPost ->
    usersPost.users.forEachIndexed { index, userJson ->
        if (userJson !is JsonObject) {
            return@validate ValidationResult.Invalid("Field users element at index $index is not a JsonObject")
        }

        val id = userJson.jsonObject["id"]?.jsonPrimitive?.content
            ?: return@validate ValidationResult.Invalid("Field id is missing at users element index $index")

        if (id.isEmpty()) {
            return@validate ValidationResult.Invalid("Field id is empty at users element index $index")
        }

        try {
            id.toLong()
        } catch (_: NumberFormatException) {
            return@validate ValidationResult.Invalid("Field id is not a number at users element index $index")
        }

        val name = userJson.jsonObject["name"]?.jsonPrimitive?.content
            ?: return@validate ValidationResult.Invalid("Field name is missing at users element index $index")

        if (name.isEmpty()) {
            return@validate ValidationResult.Invalid("Field name is empty at users element index $index")
        }
    }

    ValidationResult.Valid
}

fun RequestValidationConfig.validateUsersDelete() = validate<UsersDelete> { usersDelete ->
    usersDelete.users.forEachIndexed { index, userJson ->
        if (userJson !is JsonObject) {
            return@validate ValidationResult.Invalid("Field users element at index $index is not a JsonObject")
        }

        val id = userJson.jsonObject["id"]?.jsonPrimitive?.content
            ?: return@validate ValidationResult.Invalid("Field id is missing at users element index $index")

        if (id.isEmpty()) {
            return@validate ValidationResult.Invalid("Field id is empty at users element index $index")
        }

        try {
            id.toLong()
        } catch (_: NumberFormatException) {
            return@validate ValidationResult.Invalid("Field id is not a number at users element index $index")
        }
    }

    ValidationResult.Valid
}

fun Route.userRoute(userRepository: UserRepository) = route("/users") {
    get {
        val users = userRepository.read()

        val response = buildJsonObject {
            put("ok", true)
            put("result", buildJsonArray {
                users.map { user ->
                    add(buildJsonObject {
                        put("id", user.id)
                        put("name", user.name)
                        put("role", user.role.name.lowercase())
                        put("language", user.language)
                    })
                }
            })
        }

        call.respond(response)
    }

    post {
        val usersPost = call.receive<UsersPost>()

        val users = mutableListOf<JsonObject>()

        usersPost.users.jsonArray.forEach { jsonElement ->
            val id = jsonElement.jsonObject["id"]!!.jsonPrimitive.content.toLong()
            val name = jsonElement.jsonObject["name"]!!.jsonPrimitive.content
            val role = jsonElement.jsonObject["role"]?.jsonPrimitive?.content?.uppercase()?.let { role ->
                User.Role.valueOf(role)
            } ?: User.Role.EDITOR
            val language = jsonElement.jsonObject["language"]?.jsonPrimitive?.content ?: ""

            val user = User(id, name, role, language)

            val userExists = userRepository.read(user.id) != null

            if (userExists) {
                userRepository.update(user)
            } else userRepository.create(user)

            users.add(buildJsonObject {
                put("id", user.id)
                put("name", user.name)
                put("role", user.role.name.lowercase())
                put("language", user.language)
                put(if (userExists) "updated" else "created", true)
            })
        }

        val response = buildJsonObject {
            put("ok", true)
            put("result", buildJsonArray {
                users.map { user -> add(user) }
            })
        }

        call.respond(response)
    }

    delete {
        val usersDelete = call.receive<UsersDelete>()

        val users = mutableListOf<User>()

        usersDelete.users.jsonArray.forEach { jsonElement ->
            val id = jsonElement.jsonObject["id"]!!.jsonPrimitive.content.toLong()

            val user = userRepository.read(id)

            if (user != null && user.role != User.Role.ADMIN) {
                userRepository.delete(user.id)

                users.add(user)
            }
        }

        val response = buildJsonObject {
            put("ok", true)
            put("result", buildJsonArray {
                users.map { user ->
                    add(buildJsonObject {
                        put("id", user.id)
                        put("name", user.name)
                        put("role", user.role.name.lowercase())
                        put("language", user.language)
                    })
                }
            })
        }

        call.respond(response)
    }
}