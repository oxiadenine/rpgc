package io.github.oxiadenine.rpgc.bot.command

import io.github.oxiadenine.rpgc.common.normalize
import io.github.oxiadenine.rpgc.common.repository.*

class CharacterImageCommandHandler(
    private val gameRepository: GameRepository,
    private val characterRepository: CharacterRepository,
    private val characterImageRepository: CharacterImageRepository
) : CommandHandler() {
    private fun Game.Name.toCommandName() = value
        .normalize()
        .replace("[^a-zA-Z0-9 ]".toRegex(), "")
        .split(" ")
        .joinToString("") { it[0].lowercase() }

    private fun Character.Name.toCommandName() = value
        .normalize()
        .replace("[^a-zA-Z0-9]".toRegex(), "")
        .lowercase()

    override suspend fun handle(command: Command): CommandResult<List<CharacterImage>> {
        val characterImages = gameRepository.read().firstOrNull { game ->
            game.name.toCommandName() == command.name
        }?.let { game ->
            if (command.args.isEmpty()) {
                return CommandResult.Failure()
            }

            val nameKeywords = command.args.map { nameKeyword ->
                nameKeyword.normalize().replace("[^a-zA-Z0-9 ]".toRegex(), "")
            }

            if (nameKeywords.joinToString("").length < 3) {
                return CommandResult.Failure()
            }

            characterRepository.read(game.id).filter { character ->
                val characterName = character.name.value
                    .normalize()
                    .replace("[^a-zA-Z0-9 ]".toRegex(), "")

                if (nameKeywords.size > 1) {
                    val partialNames = characterName.split(" ")

                    nameKeywords.withIndex().all { nameKeyword ->
                        val partialName = partialNames.getOrElse(nameKeyword.index) { "" }

                        if (
                            nameKeyword.value.length > 3 &&
                            (partialName.isEmpty() || partialName.length < 3)
                        ) false else partialName.contains(nameKeyword.value, true)
                    }
                } else characterName.contains(nameKeywords[0], true)
            }.mapNotNull { character -> characterImageRepository.read(character.id) }
        } ?: run {
            if (command.name.length < 3) {
                return CommandResult.Failure()
            }

            characterRepository.read().filter { character ->
                character.name.toCommandName().contains(command.name, true)
            }.mapNotNull { character -> characterImageRepository.read(character.id) }
        }

        if (characterImages.isEmpty()) {
            return CommandResult.Failure()
        }

        return CommandResult.Success(characterImages)
    }
}