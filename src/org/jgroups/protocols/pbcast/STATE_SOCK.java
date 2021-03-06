package org.jgroups.protocols.pbcast;

import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Global;
import org.jgroups.View;
import org.jgroups.annotations.LocalAddress;
import org.jgroups.annotations.MBean;
import org.jgroups.annotations.Property;
import org.jgroups.conf.PropertyConverters;
import org.jgroups.stack.IpAddress;
import org.jgroups.util.StateTransferResult;
import org.jgroups.util.Util;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * <code>STATE_SOCK</code> has the state provider create a server socket to which the state
 * requester connects and from which the latter reads the state.
 * <p/>
 * When implementing {@link org.jgroups.MessageListener#getState(java.io.OutputStream)}, the state should be written in
 * sizeable chunks, because the underlying output stream sends 1 message / write over the socket. So if there are 1000
 * writes of 1 byte each, this would generate 1000 messages ! We suggest using a {@link java.io.BufferedOutputStream}
 * over the output stream handed to the application as argument of the callback.
 * <p/>
 * When implementing the {@link org.jgroups.MessageListener#setState(java.io.InputStream)} callback, there is no need to use a
 * {@link java.io.BufferedOutputStream}, as the input stream handed to the application already buffers incoming data
 * internally.
 * @author Vladimir Blagojevic
 * @author Bela Ban
 * @see STATE_TRANSFER
 * @since 3.0
 */
@MBean(description="State trasnfer protocol based on streaming state transfer")
public class STATE_SOCK extends StreamingStateTransfer {

    /*
     * ----------------------------------------------Properties -----------------------------------
     */
    @LocalAddress
    @Property(description="The interface (NIC) used to accept state requests. " +
      "The following special values are also recognized: GLOBAL, SITE_LOCAL, LINK_LOCAL and NON_LOOPBACK",
              systemProperty={Global.BIND_ADDR},
              defaultValueIPv4=Global.NON_LOOPBACK_ADDRESS, defaultValueIPv6=Global.NON_LOOPBACK_ADDRESS)
    protected InetAddress bind_addr;

    @Property(name="bind_interface", converter=PropertyConverters.BindInterface.class,
              description="The interface (NIC) which should be used by this transport", dependsUpon="bind_addr")
    protected String bind_interface_str=null;

    @Property(description="The port listening for state requests. Default value of 0 binds to any (ephemeral) port")
    protected int bind_port=0;


    /*
    * --------------------------------------------- Fields ---------------------------------------
    */

    /**
     * Runnable that listens for state requests and spawns threads to serve those requests if socket transport is used
     */
    protected volatile StateProviderAcceptor spawner;


    public STATE_SOCK() {
        super();
    }


    public void stop() {
        super.stop();
        if(spawner != null)
            spawner.stop();
    }


    /*
    * --------------------------- Private Methods ------------------------------------------------
    */

    protected StateProviderAcceptor createAcceptor() {
        StateProviderAcceptor retval=new StateProviderAcceptor(thread_pool,
                                                               Util.createServerSocket(getSocketFactory(),
                                                                                       Global.STATE_SERVER_SOCK,
                                                                                       bind_addr, bind_port));
        Thread t=getThreadFactory().newThread(retval, "STATE server socket acceptor");
        t.start();
        return retval;
    }


    protected void modifyStateResponseHeader(StateHeader hdr) {
        if(spawner != null)
            hdr.bind_addr=spawner.getServerSocketAddress();
    }


    protected void createStreamToRequester(Address requester) {
        ;
    }

    protected void createStreamToProvider(Address provider, StateHeader hdr) {
        IpAddress address=hdr.bind_addr;
        InputStream bis=null;
        Socket socket=new Socket();
        try {
            socket.bind(new InetSocketAddress(bind_addr, 0));
            socket.setReceiveBufferSize(buffer_size);
            Util.connect(socket, new InetSocketAddress(address.getIpAddress(), address.getPort()), 0);
            if(log.isDebugEnabled())
                log.debug(local_addr + ": connected to state provider " + address.getIpAddress() + ":" + address.getPort());

            // write out our state_id and address
            ObjectOutputStream out=new ObjectOutputStream(socket.getOutputStream());
            out.writeObject(local_addr);
            // bis=new BufferedInputStream(new StreamingInputStreamWrapper(socket), buffer_size);
            bis=new BufferedInputStream(socket.getInputStream(), buffer_size);
            setStateInApplication(provider, bis, hdr.getDigest());
        }
        catch(IOException e) {
            if(log.isWarnEnabled())
                log.warn(local_addr + ": state reader socket thread spawned abnormally", e);
            handleException(e);
        }
        finally {
            Util.close(bis);
            Util.close(socket);
        }
    }


    protected void handleStateReq(Address requester) {
        if(spawner == null || !spawner.isRunning())
            spawner=createAcceptor();
        super.handleStateReq(requester);
    }


    protected void handleViewChange(View v) {
        super.handleViewChange(v);
        if(state_provider != null && !v.getMembers().contains(state_provider)) {
            openBarrierAndResumeStable();
            Exception ex=new EOFException("state provider " + state_provider + " left");
            up_prot.up(new Event(Event.STATE_TRANSFER_INPUTSTREAM_CLOSED, new StateTransferResult(ex)));
        }
    }




    /*
    * ------------------------ End of Private Methods --------------------------------------------
    */

    protected class StateProviderAcceptor implements Runnable {
        protected final ExecutorService pool;
        protected final ServerSocket    serverSocket;
        protected final IpAddress       address;
        protected volatile boolean      running=true;

        public StateProviderAcceptor(ExecutorService pool, ServerSocket stateServingSocket) {
            super();
            this.pool=pool;
            this.serverSocket=stateServingSocket;
            this.address=new IpAddress(STATE_SOCK.this.bind_addr, serverSocket.getLocalPort());
        }

        public IpAddress getServerSocketAddress() {return address;}
        public boolean   isRunning()              {return running;}

        public void run() {
            if(log.isDebugEnabled())
                log.debug(local_addr + ": StateProviderAcceptor listening at " + getServerSocketAddress());
            while(running) {
                try {
                    final Socket socket=serverSocket.accept();
                    try {
                        pool.execute(new Runnable() {
                            public void run() {
                                process(socket);
                            }
                        });
                    }
                    catch(RejectedExecutionException rejected) {
                        Util.close(socket);
                    }
                }
                catch(Throwable e) {
                    if(serverSocket.isClosed())
                        running=false;
                }
            }
        }

        protected void process(Socket socket) {
            OutputStream      output=null;
            ObjectInputStream ois=null;
            try {
                socket.setSendBufferSize(buffer_size);
                if(log.isDebugEnabled())
                    log.debug(local_addr + ": accepted request for state transfer from " + socket.getInetAddress() + ":" + socket.getPort());

                ois=new ObjectInputStream(socket.getInputStream());
                Address stateRequester=(Address)ois.readObject();
                output=new BufferedOutputStream(socket.getOutputStream(), buffer_size);
                getStateFromApplication(stateRequester, output, false);
            }
            catch(Throwable e) {
                if(log.isWarnEnabled())
                    log.warn(local_addr + ": failed handling request from requester", e);
            }
            // getStateFromApplication() is run in the same thread; it closes the output stream, and we close the socket
            finally {
                Util.close(socket);
            }
        }



        public void stop() {
            running=false;
            try {
                getSocketFactory().close(serverSocket);
            }
            catch(Exception ignored) {
            }
        }
    }


}
