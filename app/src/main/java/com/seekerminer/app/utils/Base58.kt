package com.seekerminer.app.utils

import java.math.BigInteger

/**
 * Base58 encoding/decoding for Solana addresses
 * 
 * Uses Bitcoin/Solana alphabet (no 0, O, I, l to avoid confusion)
 */
object Base58 {
    
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)
    
    /**
     * Encode bytes to Base58 string (Solana address format)
     */
    fun encode(input: ByteArray): String {
        if (input.isEmpty()) {
            return ""
        }
        
        // Count leading zeros
        var leadingZeros = 0
        while (leadingZeros < input.size && input[leadingZeros].toInt() == 0) {
            leadingZeros++
        }
        
        // Convert to BigInteger and encode
        val inputInt = BigInteger(1, input)
        val encoded = StringBuilder()
        
        var current = inputInt
        while (current > BigInteger.ZERO) {
            val (quotient, remainder) = current.divideAndRemainder(BASE)
            encoded.insert(0, ALPHABET[remainder.toInt()])
            current = quotient
        }
        
        // Add '1' for each leading zero byte
        repeat(leadingZeros) {
            encoded.insert(0, ALPHABET[0])
        }
        
        return encoded.toString()
    }
    
    /**
     * Decode Base58 string to bytes
     */
    fun decode(input: String): ByteArray {
        if (input.isEmpty()) {
            return ByteArray(0)
        }
        
        // Count leading '1's
        var leadingOnes = 0
        while (leadingOnes < input.length && input[leadingOnes] == ALPHABET[0]) {
            leadingOnes++
        }
        
        // Decode from Base58
        var decoded = BigInteger.ZERO
        for (char in input) {
            val index = ALPHABET.indexOf(char)
            if (index == -1) {
                throw IllegalArgumentException("Invalid Base58 character: $char")
            }
            decoded = decoded.multiply(BASE).add(BigInteger.valueOf(index.toLong()))
        }
        
        // Convert to bytes
        val bytes = decoded.toByteArray()
        
        // Remove sign byte if present
        val stripSignByte = bytes.size > 1 && bytes[0].toInt() == 0 && bytes[1] < 0
        val leadingZeros = ByteArray(leadingOnes)
        
        return if (stripSignByte) {
            leadingZeros + bytes.copyOfRange(1, bytes.size)
        } else {
            leadingZeros + bytes
        }
    }
}

/**
 * Extension function: ByteArray to Base58 string
 */
fun ByteArray.toBase58(): String = Base58.encode(this)

/**
 * Extension function: String (Base58) to ByteArray
 */
fun String.decodeBase58(): ByteArray = Base58.decode(this)
