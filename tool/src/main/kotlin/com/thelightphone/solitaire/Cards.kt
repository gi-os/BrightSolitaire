package com.thelightphone.solitaire

import kotlin.random.Random

enum class Suit(val isRed: Boolean) {
    SPADES(false),
    HEARTS(true),
    DIAMONDS(true),
    CLUBS(false),
}

data class Card(val rank: Int, val suit: Suit) {

    val isRed: Boolean get() = suit.isRed

    val label: String
        get() = when (rank) {
            1 -> "A"
            11 -> "J"
            12 -> "Q"
            13 -> "K"
            else -> rank.toString()
        }
}

data class TableauCard(val card: Card, val faceUp: Boolean)

fun freshDeck(): List<Card> = Suit.entries.flatMap { suit -> (1..13).map { Card(it, suit) } }

fun shuffledDeck(seed: Long): List<Card> = freshDeck().shuffled(Random(seed))
