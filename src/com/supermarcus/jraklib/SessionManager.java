package com.supermarcus.jraklib;

import com.supermarcus.jraklib.lang.exceptions.InterfaceOutOfPoolSizeException;
import com.supermarcus.jraklib.lang.message.RakLibMessage;
import com.supermarcus.jraklib.lang.message.major.MainThreadExceptionMessage;
import com.supermarcus.jraklib.lang.message.major.MessagePoolOverflowMessage;
import com.supermarcus.jraklib.lang.message.major.UncaughtMainThreadExceptionMessage;
import com.supermarcus.jraklib.network.RakLibInterface;
import com.supermarcus.jraklib.protocol.PacketWrapper;
import com.supermarcus.jraklib.protocol.RawPacket;
import com.supermarcus.jraklib.protocol.raklib.UNCONNECTED_PING;
import com.supermarcus.jraklib.protocol.raklib.UNCONNECTED_PONG;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

public class SessionManager extends Thread {
    public static final int MAX_SERVER_INTERFACES = 25;

    private ReentrantLock threadLock = new ReentrantLock(true);

    private PacketWrapper wrapper = new PacketWrapper();

    private boolean isShutdown = false;

    private LinkedBlockingQueue<RakLibMessage> messages = new LinkedBlockingQueue<RakLibMessage>();

    private LinkedBlockingQueue<RawPacket> rawPackets = new LinkedBlockingQueue<RawPacket>();

    private int runningServer = 0;

    private RakLibInterface[] interfaces = new RakLibInterface[SessionManager.MAX_SERVER_INTERFACES];

    private LinkedList<MessageHandler> messageHandlers = new LinkedList<MessageHandler>();

    private PacketHandler rawHandler = null;

    private SessionMap map = new SessionMap();

    private long serverId = (long)(Long.MAX_VALUE * Math.random());

    volatile private String serverName = "MCPE;Minecraft Server;27;0.11.0;0;60";

    public SessionManager(){
        this.registerDefaultPackets();
        this.setName("RakLib - Main Thread");
        this.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                queueMessage(new UncaughtMainThreadExceptionMessage(t, e));
            }
        });
        this.start();
    }

    public void run(){
        while(!this.isShutdown()){
            try{
                this.processMessages();
                this.processRawPacket();
                this.setServerName("MCPE;JRakLib Server " + System.currentTimeMillis() + ";27;0.11.0;0;60");
            }catch (Throwable t){
                this.queueMessage(new MainThreadExceptionMessage(this, t));
            }
        }
    }

    public SessionMap getSessionMap(){
        return this.map;
    }

    public void setServerName(String name){
        this.serverName = name;
    }

    public String getServerName(){
        return this.serverName;
    }

    public long getServerId(){
        return this.serverId;
    }

    private void processMessages(){
        RakLibMessage message = messages.poll();
        while(message != null){
            try{
                this.fireMessage(message);
            }catch (Exception ignore){}
            message = messages.poll();
        }
    }

    private void processRawPacket(){
        RawPacket raw = rawPackets.poll();
        while(raw != null){
            try{
                if(this.rawHandler != null){
                    this.rawHandler.onRawPacket(raw);
                }
            }catch (Exception ignore){}
            raw = rawPackets.poll();
        }
    }

    private void fireMessage(RakLibMessage message){
        for(MessageHandler handler : this.messageHandlers){
            handler.onMessage(message);
        }
    }

    public void addMessageHandler(MessageHandler handler){
        Objects.requireNonNull(handler);
        this.messageHandlers.add(handler);
    }

    public RakLibInterface getInterface(int id){
        return this.interfaces[id];
    }

    public RakLibInterface[] getInterfaces(){
        RakLibInterface[] interfaces = new RakLibInterface[this.runningServer];
        int o = 0;
        for (RakLibInterface anInterface : this.interfaces) {
            if (anInterface != null) {
                interfaces[o] = anInterface;
            }
        }
        return interfaces;
    }

    public void queueMessage(RakLibMessage message){
        this.messages.offer(message);
    }

    public void queueRaw(RawPacket pk){
        this.rawPackets.offer(pk);
    }

    public boolean collectInterfaces(boolean force){
        boolean didCollect = false;
        if(force || this.threadLock.tryLock()){
            int running = 0;
            for(int i = 0; i < this.interfaces.length; ++i){
                if(this.interfaces[i] != null){
                    if(this.interfaces[i].isTerminated()){
                        this.interfaces[i] = null;
                        didCollect = true;
                    }else {
                        ++running;
                    }
                }
            }
            this.runningServer = running;
            if(!force)this.threadLock.unlock();
        }
        return didCollect;
    }

    public RakLibInterface addInterface(InetSocketAddress bindAddress) throws SocketException, InterfaceOutOfPoolSizeException {
        this.threadLock.lock();
        this.collectInterfaces(true);
        int id = nextInterfaceId();
        if(id < 0){
            throw new InterfaceOutOfPoolSizeException("try to add interface but pool size is " + this.interfaces.length);
        }
        RakLibInterface server = new RakLibInterface(bindAddress, this, id);
        this.interfaces[id] = server;
        ++this.runningServer;
        this.threadLock.unlock();
        return server;
    }

    public void setRawPacketHandler(PacketHandler handler){
        this.rawHandler = handler;
    }

    public PacketWrapper getWrapper(){
        return this.wrapper;
    }

    public void shutdown(){
        this.isShutdown = true;
    }

    public boolean isShutdown(){
        return this.isShutdown;
    }

    private int nextInterfaceId() {
        for(int i = 0; i < this.interfaces.length; ++i){
            if(this.interfaces[i] == null){
                return i;
            }
        }
        return -1;
    }

    private void registerDefaultPackets(){
        this.getWrapper().registerPacket(new UNCONNECTED_PING());
        this.getWrapper().registerPacket(new UNCONNECTED_PONG());
    }

    public class SessionMap extends ConcurrentHashMap<InetSocketAddress, Session> {
        public Session getSession(InetSocketAddress address, RakLibInterface rakLibInterface){
            synchronized (this){
                Session session;
                if(this.containsKey(address)){
                    session = this.get(address);
                }else{
                    session = new Session(SessionManager.this, address, rakLibInterface);
                    this.put(address, session);
                }
                return session;
            }
        }

        public void removeSession(InetSocketAddress address){
            synchronized (this){
                if(this.containsKey(address)){
                    this.remove(address);
                }
            }
        }

        public Session[] findSessions(final RakLibInterface rakLibInterface){
            synchronized (this){
                final ArrayList<Session> sessions = new ArrayList<Session>();
                this.forEach(new BiConsumer<InetSocketAddress, Session>() {
                    @Override
                    public void accept(InetSocketAddress address, Session session) {
                        if(session.getOwnedInterface().equals(rakLibInterface)){
                            sessions.add(session);
                        }
                    }
                });
                return (Session[]) sessions.toArray();
            }
        }

        public void update(RakLibInterface rakLibInterface, long millis){
            Session[] sessions = this.findSessions(rakLibInterface);
            for(Session session : sessions){
                session.update(millis);
            }
        }
    }
}
