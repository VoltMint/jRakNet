package xyz.voltmint.jraknet;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;

import static xyz.voltmint.jraknet.RakNetConstraints.ALREADY_CONNECTED;
import static xyz.voltmint.jraknet.RakNetConstraints.CONNECTION_REQUEST;
import static xyz.voltmint.jraknet.RakNetConstraints.CONNECTION_REQUEST_ACCEPTED;
import static xyz.voltmint.jraknet.RakNetConstraints.CONNECTION_REQUEST_FAILED;
import static xyz.voltmint.jraknet.RakNetConstraints.CONNECTION_TIMEOUT_MILLIS;
import static xyz.voltmint.jraknet.RakNetConstraints.MAXIMUM_MTU_SIZE;
import static xyz.voltmint.jraknet.RakNetConstraints.MAX_LOCAL_IPS;
import static xyz.voltmint.jraknet.RakNetConstraints.MINIMUM_MTU_SIZE;
import static xyz.voltmint.jraknet.RakNetConstraints.NEW_INCOMING_CONNECTION;
import static xyz.voltmint.jraknet.RakNetConstraints.NO_FREE_INCOMING_CONNECTIONS;
import static xyz.voltmint.jraknet.RakNetConstraints.OPEN_CONNECTION_REPLY_1;
import static xyz.voltmint.jraknet.RakNetConstraints.OPEN_CONNECTION_REPLY_2;
import static xyz.voltmint.jraknet.RakNetConstraints.OPEN_CONNECTION_REQUEST_1;
import static xyz.voltmint.jraknet.RakNetConstraints.OPEN_CONNECTION_REQUEST_2;
import static xyz.voltmint.jraknet.RakNetConstraints.RAKNET_PROTOCOL_VERSION;
import static xyz.voltmint.jraknet.RakNetConstraints.RAKNET_PROTOCOL_VERSION_MOJANG;

/**
 * @author BlackyPaw
 * @version 1.0
 */
class ClientConnection extends Connection {

    // References
    private final ClientSocket client;

    // Pre-Connection attempts:
    private int connectionAttempts;
    private long lastConnectionAttempt;

    public ClientConnection( ClientSocket client, InetSocketAddress address ) {
        super( address, ConnectionState.INITIALIZING );
        this.client = client;
        this.connectionAttempts = 0;
        this.lastConnectionAttempt = System.currentTimeMillis();

        this.sendPreConnectionRequest1( address, MAXIMUM_MTU_SIZE );
    }

    // ================================ CONNECTION ================================ //

    @Override
    protected void sendRaw( InetSocketAddress recipient, PacketBuffer buffer ) throws IOException {
        this.client.send( recipient, buffer );
    }

    @Override
    protected Logger getImplementationLogger() {
        if ( this.client == null ) {
            return null;
        }

        return this.client.getImplementationLogger();
    }

    @Override
    boolean update( long time ) {
        if ( this.getLastReceivedPacketTime() + CONNECTION_TIMEOUT_MILLIS < time ) {
            this.getImplementationLogger().trace( "Read timed out" );
            this.notifyTimeout();
            return false;
        }

        return super.update( time );
    }

    @Override
    void notifyRemoval() {
        this.client.removeConnection();
        super.notifyRemoval();
    }

    @Override
    protected void preUpdate( long time ) {
        super.preUpdate( time );

        if ( this.connectionAttempts > 10 ) {
            // Nothing to update anymore
            return;
        }

        if ( this.connectionAttempts == 10 ) {
            this.propagateConnectionAttemptFailed( "Could not initialize connection" );
            ++this.connectionAttempts;
            return;
        }

        // Send out pre-connection attempts:
        if ( this.lastConnectionAttempt + 1000L < time ) {
            this.getImplementationLogger().trace( "Trying to connect" );

            int mtuDiff = ( MAXIMUM_MTU_SIZE - MINIMUM_MTU_SIZE ) / 9;
            int mtuSize = MAXIMUM_MTU_SIZE - ( this.connectionAttempts * mtuDiff );
            if ( mtuSize < MINIMUM_MTU_SIZE ) {
                mtuSize = MINIMUM_MTU_SIZE;
            }

            this.sendPreConnectionRequest1( this.getAddress(), mtuSize );

            ++this.connectionAttempts;
            this.lastConnectionAttempt = time;
        }
    }

    @Override
    protected boolean handleDatagram0( InetSocketAddress sender, PacketBuffer datagram, long time ) {
        this.lastPingTime = time;

        // Handle special internal packets:
        byte packetId = datagram.getBuffer().getByte(0);
        switch ( packetId ) {
            case OPEN_CONNECTION_REPLY_1:
                this.handlePreConnectionReply1( sender, datagram );
                return true;
            case OPEN_CONNECTION_REPLY_2:
                this.handlePreConnectionReply2( datagram );
                return true;
            case ALREADY_CONNECTED:
                this.handleAlreadyConnected( sender, datagram );
                return true;
            case NO_FREE_INCOMING_CONNECTIONS:
                this.handleNoFreeIncomingConnections( sender, datagram );
                return true;
            case CONNECTION_REQUEST_FAILED:
                this.handleConnectionRequestFailed( sender, datagram );
                return true;
            default:
                return false;
        }
    }

    @Override
    protected boolean handlePacket0( EncapsulatedPacket packet ) {
        // Handle special internal packets:
        byte packetId = packet.getPacketData().getByte(0);
        if ( packetId == CONNECTION_REQUEST_ACCEPTED ) {
            this.handleConnectionRequestAccepted( packet );
            return true;
        }

        return false;
    }

    @Override
    protected void propagateConnectionClosed() {
        this.client.propagateConnectionClosed( this );
    }

    @Override
    protected void propagateConnectionDisconnected() {
        this.client.propagateConnectionDisconnected( this );
    }

    @Override
    protected void propagateFullyConnected() {
        this.client.propagateConnectionRequestSucceded( this );
    }

    private void propagateConnectionAttemptFailed( String reason ) {
        this.client.propagateConnectionAttemptFailed( reason );
    }

    // ================================ PACKET HANDLERS ================================ //

    private void handlePreConnectionReply1( InetSocketAddress sender, PacketBuffer datagram ) {
        // Prevent further connection attempts:
        this.connectionAttempts = 11;

        datagram.skip( 1 );                                       // Packet ID
        datagram.readOfflineMessageDataId();                      // Offline Message Data ID
        this.setGuid( datagram.readLong() );                      // Server GUID
        boolean securityEnabled = datagram.readBoolean();         // Security Enabled
        this.setMtuSize( datagram.readUShort() );                 // MTU Size

        if ( securityEnabled ) {
            // We don't support security:
            this.setState( ConnectionState.UNCONNECTED );
            this.propagateConnectionAttemptFailed( "Security is not supported" );
            return;
        }

        this.sendPreConnectionRequest2( sender );
    }

    private void handlePreConnectionReply2( PacketBuffer datagram ) {
        if ( this.getState() != ConnectionState.INITIALIZING ) {
            return;
        }

        datagram.skip( 1 );                                                                       // Packet ID
        datagram.readOfflineMessageDataId();                                                      // Offline Message Data ID
        if ( this.getGuid() != datagram.readLong() ) {                                            // Server GUID
            this.setState( ConnectionState.UNCONNECTED );
            this.propagateConnectionAttemptFailed( "Server send different GUIDs during pre-connect" );
            return;
        }

        this.setMtuSize( datagram.readUShort() );                                                 // MTU Size
        datagram.readBoolean();                                                                   // Security Enabled

        this.initializeStructures();
        this.setState( ConnectionState.RELIABLE );

        this.sendConnectionRequest();
    }

    @SuppressWarnings( "unused" )
    private void handleAlreadyConnected( InetSocketAddress sender, PacketBuffer datagram ) {
        this.setState( ConnectionState.UNCONNECTED );
        this.propagateConnectionAttemptFailed( "System is already connected" );
    }

    @SuppressWarnings( "unused" )
    private void handleNoFreeIncomingConnections( InetSocketAddress sender, PacketBuffer datagram ) {
        this.setState( ConnectionState.UNCONNECTED );
        this.propagateConnectionAttemptFailed( "Remote peer has no free incoming connections left" );
    }

    @SuppressWarnings( "unused" )
    private void handleConnectionRequestFailed( InetSocketAddress sender, PacketBuffer datagram ) {
        this.setState( ConnectionState.UNCONNECTED );
        this.propagateConnectionAttemptFailed( "Remote peer rejected connection request" );
    }

    private void handleConnectionRequestAccepted( EncapsulatedPacket packet ) {
        PacketBuffer buffer = new PacketBuffer( packet.getPacketData() );
        buffer.skip( 1 );                                                                       // Packet ID
        buffer.readAddress();                                                                   // Client Address
        buffer.readUShort();                                                                    // Remote System Index (not always applicable)

        for ( int i = 0; i < MAX_LOCAL_IPS; ++i ) {
            buffer.readAddress();                                                               // Server Local IPs
        }

        buffer.readLong();                                                                     // Ping Time
        long pongTime = buffer.readLong();                                                      // Pong Time

        // Send response:
        this.sendNewIncomingConnection( pongTime );

        // Finally we are connected!
        this.setState( ConnectionState.CONNECTED );
    }

    // ================================ PACKET SENDERS ================================ //

    private void sendPreConnectionRequest1( InetSocketAddress recipient, int mtuSize ) {
        this.setState( ConnectionState.INITIALIZING );

        PacketBuffer buffer = new PacketBuffer( MAXIMUM_MTU_SIZE );
        buffer.writeByte( OPEN_CONNECTION_REQUEST_1 );
        buffer.writeOfflineMessageDataId();
        buffer.writeByte( this.client.mojangModificationEnabled ? RAKNET_PROTOCOL_VERSION_MOJANG : RAKNET_PROTOCOL_VERSION );

        // Simulate filling with zeroes, in order to "test out" maximum MTU size:
        byte[] data = new byte[mtuSize - ( 2 + RakNetConstraints.OFFLINE_MESSAGE_DATA_ID.length + RakNetConstraints.DATA_HEADER_BYTE_LENGTH)];
        buffer.writeBytes( data );

        try {
            this.sendRaw( recipient, buffer );
        } catch ( IOException e ) {
            // ._.
        }
    }

    private void sendPreConnectionRequest2( InetSocketAddress recipient ) {
        PacketBuffer buffer = new PacketBuffer( 34 );
        buffer.writeByte( OPEN_CONNECTION_REQUEST_2 );          // Packet ID
        buffer.writeOfflineMessageDataId();                     // Offline Message Data ID
        buffer.writeAddress( recipient );                       // Client Bind Address
        buffer.writeUShort( this.getMtuSize() );                // MTU size
        buffer.writeLong( this.client.getGuid() );              // Client GUID

        try {
            this.sendRaw( recipient, buffer );
        } catch ( IOException e ) {
            // ._.
        }
    }

    private void sendConnectionRequest() {
        PacketBuffer buffer = new PacketBuffer( 18 );
        buffer.writeByte( CONNECTION_REQUEST );                 // Packet ID
        buffer.writeLong( this.client.getGuid() );              // Client GUID
        buffer.writeLong( System.currentTimeMillis() );         // Ping Time
        buffer.writeBoolean( false );                           // Security Enabled

        this.send( PacketReliability.RELIABLE_ORDERED, 0, buffer );
    }

    private void sendNewIncomingConnection( long pingTime ) {
        PacketBuffer buffer = new PacketBuffer( 94 );
        buffer.writeByte( NEW_INCOMING_CONNECTION );
        buffer.writeAddress( this.getAddress() );
        for ( int i = 0; i < MAX_LOCAL_IPS; ++i ) {
            buffer.writeAddress( ServerConnection.LOCAL_IP_ADDRESSES[i] );
        }
        buffer.writeLong( pingTime );
        buffer.writeLong( System.currentTimeMillis() );

        this.send( PacketReliability.RELIABLE_ORDERED, 0, buffer );
    }

}