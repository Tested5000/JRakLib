package com.supermarcus.jraklib.protocol;

import com.supermarcus.jraklib.protocol.raklib.PacketInfo;

import java.nio.ByteBuffer;

abstract public class Packet {
    public static final int MAX_SIZE = 1024 * 1024 * 8;

    private ByteBuffer buffer = ByteBuffer.allocate(Packet.MAX_SIZE);

    private int networkID = 0;

    public Packet(int id){
        this.networkID = id;
        this.getBuffer().put((byte) this.getNetworkID());
    }

    abstract public void encode();

    abstract public void decode();

    public int getNetworkID(){
        return networkID;
    }

    public PacketInfo getPacketIdentifier(){
        return PacketInfo.getById((byte) this.getNetworkID());
    }

    protected ByteBuffer getBuffer(){
        return buffer;
    }

    public void initBuffer(ByteBuffer buffer){
        this.buffer = buffer;
        this.networkID = this.getBuffer().get();
    }

    public byte[] toRaw(){
        int length = getBuffer().position() + 1;
        byte[] raw = new byte[length];
        System.arraycopy(getBuffer().array(), 0, raw, 0, length);
        return raw;
    }
}
