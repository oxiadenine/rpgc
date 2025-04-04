package io.github.oxiadenine.rpgc.common

import java.text.Normalizer

fun String.normalize() = Normalizer.normalize(this, Normalizer.Form.NFKD).replace("\\p{M}".toRegex(), "")