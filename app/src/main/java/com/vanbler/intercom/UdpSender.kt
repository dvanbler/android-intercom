package com.vanbler.intercom

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpSender(private val address: InetAddress, private val port: Int) {

    private val socket = DatagramSocket()

    fun send(data: ByteArray) {
        val packet = DatagramPacket(data, data.size, address, port)
        socket.send(packet)
    }

    fun sendTo(data: ByteArray, destPort: Int) {
        val packet = DatagramPacket(data, data.size, address, destPort)
        socket.send(packet)
    }

    fun close() {
        socket.close()
    }
}