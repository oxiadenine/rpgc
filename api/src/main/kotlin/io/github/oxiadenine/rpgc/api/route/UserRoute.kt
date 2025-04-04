package io.github.oxiadenine.rpgc.api.route

import io.github.oxiadenine.rpgc.common.repository.User
import io.github.oxiadenine.rpgc.common.repository.UserRepository
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Routing.userRoute(userRepository: UserRepository) = route("/users") {
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
        val body = call.receive<JsonObject>()

        val users = mutableListOf<JsonObject>()

        body["users"]!!.jsonArray.forEach { jsonElement ->
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
        val body = call.receive<JsonObject>()

        val users = mutableListOf<User>()

        body["users"]!!.jsonArray.forEach { jsonElement ->
            val userId = jsonElement.jsonObject["id"]!!.jsonPrimitive.content.toLong()

            val user = userRepository.read(userId)

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