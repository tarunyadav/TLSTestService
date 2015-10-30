package tlstestservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlstestservice.messages.Certificate;
import tlstestservice.messages.*;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.interfaces.DHPrivateKey;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

/**
 * @author Joeri de Ruiter (j.deruiter@cs.bham.ac.uk)
 * @author Mark Janssen (mark@ch.tudelft.nl)
 */
public class TLSTestService {
    
    private static final Logger log = LoggerFactory.getLogger(TLSTestService.class);
    
    Socket socket;
    OutputStream output;
    InputStream input;

    String host = "127.0.0.1";
    int port = 4433;

    boolean DEBUG = false;

    // Act as a TLS client - set by 'target' configuration setting
    boolean CLIENT_MODE;

    // Restart server after every session
    boolean REQUIRE_RESTART = false;

    // Timeout in ms
    int RECEIVE_MSG_TIMEOUT = 100;

    // Enable the Heartbeat extension in the ClientHello message
    boolean ENABLE_HEARTBEAT = true;

    // Send output from TLS implementation to console
    boolean CONSOLE_OUTPUT = true;

    // Set default TLS version to use
    TLS currentTLS = new TLS12();
//	TLS currentTLS = new TLS10();

    // Special modes to trigger bugs in OpenSSL
    boolean OPENSSL_MODE = true;
    boolean REUSE_KEYBLOCK = false;


    SecureRandom rand;

    byte[] master_secret;
    byte[] verify_data;

    Cipher readCipher;
    Mac readMAC;
    long readMACSeqNr;

    Cipher writeCipher;
    Mac writeMAC;
    long writeMACSeqNr;

    CipherSuite cipherSuite;
    PublicKey serverKey;
    X509Certificate serverCertificate;
    PrivateKey serverPrivateKey;

    PublicKey clientKey;
    X509Certificate clientCertificate;
    PrivateKey clientPrivateKey;

    byte[] client_random = new byte[32];
    byte[] server_random = new byte[32];
    byte[] session_id = new byte[]{};

    CipherSuite initCipherSuite;
    PublicKey initServerKey;

    DHPublicKey dhPubKey;
    DHPrivateKey dhPrivateKey;

    byte[] handshakeMessages = {};

    boolean ccs_in = false;
    boolean ccs_out = false;

    byte[] tmp_key_block;

    String cmd;

    Process targetProcess;
    TLSClient tlsClient;


    public static TLSTestService createTLSServerTestService(String cmd, int port, boolean restart) throws Exception {
        TLSTestService service = new TLSTestService();
        service.setTarget("server");
        service.setCommand(cmd);
        service.setPort(port);
        service.setRestartTarget(restart);
        service.start();
        return service;
    }

    public static TLSTestService createTLSClientTestService(String cmd, String host, int port) throws Exception {
        TLSTestService service = new TLSTestService();
        service.setTarget("client");
        service.setCommand(cmd);
        service.setHost(host);
        service.setPort(port);
        service.start();

        return service;
    }

    public TLSTestService() throws Exception {
        rand = new SecureRandom();
        setInitValues();
    }

    public void setTarget(String target) throws Exception {
        if (target.equals("server")) {
            CLIENT_MODE = true;
        } else if (target.equals("client")) {
            CLIENT_MODE = false;
        } else {
            throw new Exception("Unknown target");
        }
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setCommand(String cmd) {
        this.cmd = cmd;
    }

    public void setRestartTarget(boolean restart) {
        this.REQUIRE_RESTART = restart;
    }


    public void start() throws Exception {
        if (CLIENT_MODE) {
            loadClientKey();

            if (cmd != null && !cmd.equals("")) {
                ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));

                if (CONSOLE_OUTPUT) {
                    pb.inheritIO();
                } else {
                    pb.redirectError(new File("error.log"));
                    pb.redirectOutput(new File("output.log"));
                }

                targetProcess = pb.start();


                Thread.sleep(5000);
            }

            connectSocket();

            retrieveInitValues();
        } else {
            loadServerKey();

            ServerSocket serverSocket = listenSocket();

            if (cmd != null && !cmd.equals("")) {
                ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));

                if (CONSOLE_OUTPUT) {
                    pb.inheritIO();
                } else {
                    pb.redirectError(new File("error.log"));
                    pb.redirectOutput(new File("output.log"));
                }

                targetProcess = pb.start();
                tlsClient = new TLSClient(targetProcess);
            }

            // Wait for the client to send the first message (ClientHello)
            socket = serverSocket.accept();

            socket.setTcpNoDelay(true);
            socket.setSoTimeout(RECEIVE_MSG_TIMEOUT);
            output = socket.getOutputStream();
            input = socket.getInputStream();

            serverSocket.close();
            receiveMessages();
        }
    }

    public void reset() throws Exception {
        //log.debug("RESET");
        socket.close();
        setInitValues();

        if (CLIENT_MODE) {
            if (REQUIRE_RESTART && cmd != null && !cmd.equals("")) {
                targetProcess.destroy();

                Thread.sleep(500);

                ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));

                if (CONSOLE_OUTPUT) {
                    pb.inheritIO();
                } else {
                    pb.redirectError(new File("error.log"));
                    pb.redirectOutput(new File("output.log"));
                }

                targetProcess = pb.start();

                Thread.sleep(200);
            }

            connectSocket();

            // Reset to initial values
            cipherSuite = initCipherSuite;
            serverKey = initServerKey;
        } else {
            if (targetProcess != null) {
                targetProcess.destroy();
            }

            ServerSocket serverSocket = listenSocket();

            if (cmd != null && !cmd.equals("")) {
                ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));

                if (CONSOLE_OUTPUT) {
                    pb.inheritIO();
                } else {
                    pb.redirectError(new File("error.log"));
                    pb.redirectOutput(new File("output.log"));
                }

                targetProcess = pb.start();
                tlsClient = new TLSClient(targetProcess);
            }

            // Wait for the client to send the first message (ClientHello)
            socket = serverSocket.accept();

            socket.setTcpNoDelay(true);
            socket.setSoTimeout(RECEIVE_MSG_TIMEOUT);
            output = socket.getOutputStream();
            input = socket.getInputStream();

            serverSocket.close();
            receiveMessages();
        }
    }

    public void setRequireRestart(boolean enable) {
        REQUIRE_RESTART = enable;
    }

    public void setDebugging(boolean enable) {
        DEBUG = enable;
    }

    public void setReceiveMessagesTimeout(int timeout) {
        RECEIVE_MSG_TIMEOUT = timeout;
    }

    public void setOpenSSLMode(boolean enable) {
        OPENSSL_MODE = enable;
    }

    public void setConsoleOutepu(boolean enable) {
        CONSOLE_OUTPUT = enable;
    }

    public void useTLS10() {
        currentTLS = new TLS10();
    }

    public void useTLS12() {
        currentTLS = new TLS12();
    }

    public void connectSocket() throws UnknownHostException, IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(RECEIVE_MSG_TIMEOUT);

        output = socket.getOutputStream();
        input = socket.getInputStream();
    }

    public ServerSocket listenSocket() throws UnknownHostException, IOException {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(host, port));
        return server;
    }

    public void closeSocket() throws IOException {
        socket.close();
    }

    public void retrieveInitValues() throws Exception {
        sendClientHelloAll();

        // Set initial values
        initCipherSuite = cipherSuite;
        initServerKey = serverKey;

        if (REQUIRE_RESTART) {
            reset();
        } else {
            socket.close();
            connectSocket();
        }
    }

    public void setInitValues() throws Exception {
        master_secret = new byte[]{};
        handshakeMessages = new byte[]{};
        verify_data = currentTLS.verifyDataClient(master_secret, handshakeMessages);

        session_id = new byte[]{};

        Arrays.fill(client_random, (byte) 0x00);
        Arrays.fill(server_random, (byte) 0x00);

        ccs_in = false;
        ccs_out = false;

        cipherSuite = new CipherSuite(CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA);
    }

    void setCiphersClient() throws Exception {
        // Compute key block
        byte[] key_block = currentTLS.keyblock(master_secret, server_random, client_random);

        // OpenSSL reusing keys bug
        if (OPENSSL_MODE) {
            if (!REUSE_KEYBLOCK) {
                tmp_key_block = key_block;
                REUSE_KEYBLOCK = true;
            } else key_block = tmp_key_block;
        }

        if (DEBUG) {
            log.debug("server_random: " + Utils.bytesToHexString(server_random));
            log.debug("client_random: " + Utils.bytesToHexString(client_random));
            log.debug("key_block: " + Utils.bytesToHexString(key_block));
        }

        // Extract keys from key block
        int index = 0;

        byte[] client_write_MAC_key = Arrays.copyOfRange(key_block, index, index + cipherSuite.hashSize);
        index += 2 * cipherSuite.hashSize;

        byte[] client_write_key = Arrays.copyOfRange(key_block, index, index + cipherSuite.encCipherKeySize);
        if (DEBUG) log.debug("client_write_key: " + Utils.bytesToHexString(client_write_key));
        index += 2 * cipherSuite.encCipherKeySize;

        byte[] client_write_iv = null;

        if (cipherSuite.ivSize > 0) {
            client_write_iv = Arrays.copyOfRange(key_block, index, index + cipherSuite.ivSize);
        }
        if (DEBUG) log.debug("client_write_iv: " + Utils.bytesToHexString(client_write_iv));

        SecretKey clientCipherKey = new SecretKeySpec(client_write_key, cipherSuite.encCipherKeyAlg);

        IvParameterSpec clientCipherIV = null;
        if (client_write_iv != null) clientCipherIV = new IvParameterSpec(client_write_iv);

        if (CLIENT_MODE) {
            // Set up MAC cipher
            writeMAC = cipherSuite.getMAC();
            writeMAC.init(new SecretKeySpec(client_write_MAC_key, cipherSuite.macCipherAlg));
            writeMACSeqNr = 0;

            // Set up encryption cipher
            writeCipher = cipherSuite.getEncCipher();
            writeCipher.init(Cipher.ENCRYPT_MODE, clientCipherKey, clientCipherIV);
        } else {
            if (DEBUG) log.debug("Setting read keys for client");
            // Set up MAC cipher
            readMAC = cipherSuite.getMAC();
            readMAC.init(new SecretKeySpec(client_write_MAC_key, cipherSuite.macCipherAlg));
            readMACSeqNr = 0;

            // Set up encryption cipher
            readCipher = cipherSuite.getEncCipher();
            readCipher.init(Cipher.DECRYPT_MODE, clientCipherKey, clientCipherIV);
        }
    }

    public void setCiphersServer() throws Exception {
        // Compute key block
        byte[] key_block = currentTLS.keyblock(master_secret, server_random, client_random);

        // OpenSSL reusing keys bug
        if (OPENSSL_MODE) {
            if (!REUSE_KEYBLOCK) {
                tmp_key_block = key_block;
                REUSE_KEYBLOCK = true;
            } else key_block = tmp_key_block;
        }

        if (DEBUG) {
            log.debug("server_random: " + Utils.bytesToHexString(server_random));
            log.debug("client_random: " + Utils.bytesToHexString(client_random));
            log.debug("key_block: " + Utils.bytesToHexString(key_block));
        }

        // Extract keys from key block
        int index = cipherSuite.hashSize;

        byte[] server_write_MAC_key = Arrays.copyOfRange(key_block, index, index + cipherSuite.hashSize);
        index += cipherSuite.hashSize + cipherSuite.encCipherKeySize;

        byte[] server_write_key = Arrays.copyOfRange(key_block, index, index + cipherSuite.encCipherKeySize);
        if (DEBUG) log.debug("server_write_key: " + Utils.bytesToHexString(server_write_key));
        index += cipherSuite.encCipherKeySize;

        byte[] server_write_iv = null;
        if (cipherSuite.ivSize > 0) {
            index += cipherSuite.ivSize;
            server_write_iv = Arrays.copyOfRange(key_block, index, index + cipherSuite.ivSize);
        }
        if (DEBUG) log.debug("server_write_iv: " + Utils.bytesToHexString(server_write_iv));

        SecretKey serverCipherKey = new SecretKeySpec(server_write_key, cipherSuite.encCipherKeyAlg);

        IvParameterSpec serverCipherIV = null;
        if (server_write_iv != null) {
            serverCipherIV = new IvParameterSpec(server_write_iv);
        }

        if (CLIENT_MODE) {
            // Set up MAC cipher
            readMAC = cipherSuite.getMAC();
            readMAC.init(new SecretKeySpec(server_write_MAC_key, cipherSuite.macCipherAlg));
            readMACSeqNr = 0;

            // Set up encryption cipher
            readCipher = cipherSuite.getEncCipher();
            readCipher.init(Cipher.DECRYPT_MODE, serverCipherKey, serverCipherIV, rand);
        } else {
            if (DEBUG) log.debug("Setting write keys for server");

            // Set up MAC cipher
            writeMAC = cipherSuite.getMAC();
            writeMAC.init(new SecretKeySpec(server_write_MAC_key, cipherSuite.macCipherAlg));
            writeMACSeqNr = 0;

            // Set up encryption cipher
            writeCipher = cipherSuite.getEncCipher();
            writeCipher.init(Cipher.ENCRYPT_MODE, serverCipherKey, serverCipherIV, rand);
        }
    }

    public String receiveMessages() throws Exception {
        String out = "";

        byte contentType = 0;
        try {
            contentType = (byte) (input.read() & 0xFF);

            if (contentType == (byte) 0x80) {
                //SSLv2
                log.debug("SSLv2");
                // Read length
                input.read();
            }
        } catch (SocketTimeoutException e) {
            return "Empty";
        }

        if (contentType == -1) {
            // We got to the end of the stream
            socket.close();

            //return "ConnectionClosedEOF";
            return "ConnectionClosed";
        }

        Record record;
        while (input.available() > 0) {
            if (contentType > 0) {
                record = new Record(contentType, input);
                contentType = 0;
            } else {
                record = new Record(input);
            }

            if (ccs_in) {
                try {
                    record.decrypt(readCipher, cipherSuite.hashSize);

                    if (DEBUG) {
                        log.debug("Decrypted content: " + Utils.bytesToHexString(record.getPayload()));
                        log.debug("MAC: " + Utils.bytesToHexString(record.getMAC()));
                    }
                } catch (Exception e) {
                    if (DEBUG) e.printStackTrace();

                    out += "DecryptError";
                    break;
                }
                if (!record.checkMAC(readMAC, readMACSeqNr)) {
                    readMACSeqNr++;
                    //out += "MACError";
                    out += "DecryptError";
                    break;
                }
                readMACSeqNr++;
            }

            ByteArrayInputStream payloadStream = new ByteArrayInputStream(record.getPayload());

            while (payloadStream.available() > 0) {
                switch (record.getContentType()) {
                    case TLS.CONTENT_TYPE_ALERT:
                        out += "Alert";
                        Alert alert = new Alert(payloadStream);

                        // Check if the alert level is valid
                        if (alert.getLevel() >= 1 && alert.getLevel() <= 2)
                            out += alert.getLevel() + "." + alert.getDescription();
                        else {
                            out += "Malformed";
                            log.debug(Utils.bytesToHexString(record.getPayload()));
                        }
                        break;

                    case TLS.CONTENT_TYPE_HANDSHAKE:
                        out += "Handshake";
                        HandshakeMsg handshake = new HandshakeMsg(payloadStream);

                        if (DEBUG)
                            log.debug("Adding to handshake buffer (incoming message): " + Utils.bytesToHexString(handshake.getBytes()));

                        handshakeMessages = Utils.concat(handshakeMessages, handshake.getBytes());

                        switch (handshake.getType()) {
                            case TLS.HANDSHAKE_MSG_TYPE_CLIENT_HELLO:
                                out += "ClientHello";

                                ClientHello ch = new ClientHello(handshake);

                                out += ch.getProtocolVersion().toString();

                                client_random = ch.getRandom();
                                break;

                            case TLS.HANDSHAKE_MSG_TYPE_SERVER_HELLO:
                                out += "ServerHello";

                                ServerHello sh = new ServerHello(handshake);

                                out += sh.getProtocolVersion().toString();

                                cipherSuite = sh.getCipherSuite();
                                server_random = sh.getRandom();
                                session_id = sh.getSessionId();
                                // Ignore session id, compression method and extensions as we don't use these at the moment

                                if (DEBUG)
                                    log.debug("Server protocol version: " + sh.getProtocolVersion().toString());

                                break;

                            case TLS.HANDSHAKE_MSG_TYPE_CERTIFICATE:
                                out += "Certificate";

                                Certificate cert = new Certificate(handshake);
                                if (cert.getPublicKey() == null) out += "Empty";

                                serverKey = cert.getPublicKey();
                                break;

                            case TLS.HANDSHAKE_MSG_TYPE_CLIENT_KEY_EXCHANGE:
                                out += "ClientKeyExchange";

                                ClientKeyExchange cke = new ClientKeyExchange(handshake);

                                byte[] premaster_secret_server = new byte[]{};

                                switch (cipherSuite.keyExchange) {
                                    case CipherSuite.ALG_RSA:
                                        Cipher cipher = cipherSuite.keyExchangeCipher;
                                        cipher.init(Cipher.UNWRAP_MODE, serverPrivateKey, rand);
                                        premaster_secret_server = cipher.unwrap(cke.getExchangeKeys(), "", Cipher.SECRET_KEY).getEncoded();
                                        master_secret = currentTLS.masterSecret(premaster_secret_server, server_random, client_random);

                                        break;

                                    case CipherSuite.ALG_DHE_RSA:
                                        // Get premaster secret
                                        KeyFactory keyFactory = KeyFactory.getInstance("DH");
                                        DHPublicKeySpec pubKeySpec = new DHPublicKeySpec(new BigInteger(1, cke.getExchangeKeys()), dhPrivateKey.getParams().getP(), dhPrivateKey.getParams().getG());
                                        DHPublicKey pubKey = (DHPublicKey) keyFactory.generatePublic(pubKeySpec);
                                        KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
                                        keyAgreement.init(dhPrivateKey);
                                        keyAgreement.doPhase(pubKey, true);

                                        premaster_secret_server = keyAgreement.generateSecret();
                                        if (DEBUG)
                                            log.debug("premaster_secret: " + Utils.bytesToHexString(premaster_secret_server));

                                        // Remove 0x00s from the beginning of the shared secret
                                        int i;
                                        for (i = 0; i < premaster_secret_server.length; i++) {
                                            if (premaster_secret_server[i] != 0x00) break;
                                        }
                                        premaster_secret_server = Arrays.copyOfRange(premaster_secret_server, i, premaster_secret_server.length);

                                        // Generate master secret
                                        master_secret = currentTLS.masterSecret(premaster_secret_server, server_random, client_random);

                                        break;
                                }

                                if (DEBUG) {
                                    log.debug("Premaster secret: " + Utils.bytesToHexString(premaster_secret_server));
                                    log.debug("Master secret: " + Utils.bytesToHexString(master_secret));
                                }

                                break;

                            case TLS.HANDSHAKE_MSG_TYPE_SERVER_KEY_EXCHANGE:
                                out += "ServerKeyExchange";

                                byte[] premaster_secret_client = new byte[]{};

                                switch (cipherSuite.keyExchange) {
                                    case CipherSuite.ALG_DHE_RSA:
                                        ServerKeyExchange ske = new ServerKeyExchange(handshake);

                                        // Generate DH key
                                        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DiffieHellman");
                                        keyPairGenerator.initialize(new DHParameterSpec(ske.getP(), ske.getG()));
                                        KeyPair keyPair = keyPairGenerator.generateKeyPair();
                                        dhPubKey = (DHPublicKey) keyPair.getPublic();

                                        // Get premaster secret
                                        KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
                                        keyAgreement.init(keyPair.getPrivate());
                                        keyAgreement.doPhase(ske.getPublicKey(), true);
                                        premaster_secret_client = keyAgreement.generateSecret();

                                        // Remove 0x00s from the beginning of the shared secret
                                        int i;
                                        for (i = 0; i < premaster_secret_client.length; i++) {
                                            if (premaster_secret_client[i] != 0x00) break;
                                        }
                                        premaster_secret_client = Arrays.copyOfRange(premaster_secret_client, i, premaster_secret_client.length);

                                        // Generate master secret
                                        master_secret = currentTLS.masterSecret(premaster_secret_client, server_random, client_random);
                                        break;

                                    default:
                                        throw new Exception("ServerKeyExchange unsupported for this cipher");
                                }

                                if (DEBUG) {
                                    log.debug("Premaster secret: " + Utils.bytesToHexString(premaster_secret_client));
                                    log.debug("Master secret: " + Utils.bytesToHexString(master_secret));
                                }

                                break;

                            case TLS.HANDSHAKE_MSG_TYPE_SERVER_HELLO_DONE:
                                out += "ServerHelloDone";
                                break;

                            case TLS.HANDSHAKE_MSG_TYPE_FINISHED:
                                out += "Finished";
                                break;

                            case TLS.HANDSHAKE_MSG_TYPE_CERTIFICATE_REQUEST:
                                out += "CertificateRequest";
                                CertificateRequest cr = new CertificateRequest(handshake);
                                break;

                            case TLS.HANDSHAKE_MSG_TYPE_CERTIFICATE_VERIFY:
                                out += "CertificateVerify";
                                break;

                            default:
                                out += "Unknown";
                                log.debug("Unknown handshake message type: " + handshake.getType());
                                break;
                        }
                        break;

                    case TLS.CONTENT_TYPE_CCS:
                        out += "ChangeCipherSpec";

                        // Read 1 byte, should be 0x01
                        payloadStream.read();

                        ccs_in = true;

                        //if(!OPENSSL_MODE) {
                        if (CLIENT_MODE) setCiphersServer();
                        else setCiphersClient();
                        //}

                        break;

                    case TLS.CONTENT_TYPE_APPLICATION:
                        out += "ApplicationData";
                        payloadStream.skip(record.getLength());
                        if (DEBUG) log.debug("ApplicationData: " + record.getPayload().toString());
                        break;

                    case TLS.CONTENT_TYPE_HEARTBEAT:
                        // Read msg type
                        byte msg_type = (byte) payloadStream.read();
                        payloadStream.skip(record.getLength());

                        if (msg_type == TLS.HEARTBEAT_MSG_TYPE_REQUEST) {
                            if (out.endsWith("HeartbeatRequestMultiple")) {
                            } else if (out.endsWith("HeartbeatRequest")) out += "Multiple";
                            else out += "HeartbeatRequest";
                        } else if (msg_type == TLS.HEARTBEAT_MSG_TYPE_RESPONSE) {
                            if (out.endsWith("HeartbeatResponseMultiple")) {
                            } else if (out.endsWith("HeartbeatResponse")) out += "Multiple";
                            else out += "HeartbeatResponse";
                        } else {
                            out += "HeartbeatUnknown";
                        }
                        break;

                    default:
                        throw new Exception("Received unkown content type: " + record.getContentType());
                }
            }

            try {
                contentType = (byte) input.read();
            } catch (SocketTimeoutException e) {
                break;
            }

            if (contentType == -1) {
                // We got to the end of the stream
                socket.close();
                //out += "ConnectionClosedEOF";
                out += "ConnectionClosed";
                break;
            }
        }

        if (out.length() == 0)
            out = "Empty";

        return out;
    }

    void sendMessage(byte type, byte[] msg) throws Exception {
        Record record = new Record(type, currentTLS.getProtocolVersion(), msg);

        if (ccs_out) {
            if (DEBUG)
                log.debug("Sending record (before encryption): " + Utils.bytesToHexString(record.getBytes()));

            // Add MAC and encryption if requested
            record.addMAC(writeMAC, cipherSuite.hashSize, writeMACSeqNr);
            writeMACSeqNr++;
            record.encrypt(writeCipher, rand);
        }

        if (DEBUG) log.debug("Sending record: " + Utils.bytesToHexString(record.getBytes()));
        output.write(record.getBytes());
    }

    void sendHandshakeMessage(HandshakeMsg msg) throws Exception {
        sendHandshakeMessage(msg, true);
    }

    void sendHandshakeMessage(HandshakeMsg msg, boolean updateHash) throws Exception {
        if (updateHash) {
            if (DEBUG)
                log.debug("Adding to handshake buffer (outgoing message): " + Utils.bytesToHexString(msg.getBytes()));
            handshakeMessages = Utils.concat(handshakeMessages, msg.getBytes());
        }

        sendMessage(TLS.CONTENT_TYPE_HANDSHAKE, msg.getBytes());
    }

    public String sendHeartbeatRequest() throws Exception {
        byte[] msg = new byte[]{
                TLS.HEARTBEAT_MSG_TYPE_REQUEST, // Message type
                0x00, 0x01, // Payload length
                (byte) 0xFF, // Payload
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 // Random padding
        };

        sendMessage(TLS.CONTENT_TYPE_HEARTBEAT, msg);

        return receiveMessages();
    }

    public String sendHeartbeatResponse() throws Exception {
        byte[] msg = new byte[]{
                TLS.HEARTBEAT_MSG_TYPE_RESPONSE, // Message type
                0x00, 0x01, // Payload length
                (byte) 0xFF, // Payload
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 // Random padding
        };

        sendMessage(TLS.CONTENT_TYPE_HEARTBEAT, msg);

        return receiveMessages();
    }

    public String sendServerHelloRSA() throws Exception {
        // Generate server_random
        rand.nextBytes(server_random);

        byte[] extensions = new byte[]{};

        // Add renegotiation extension (needed for miTLS)
        byte[] renegotiation_extension;

        renegotiation_extension = new byte[]{(byte) 0xFF, 0x01, 0x00, 0x01, 0x00};
        extensions = Utils.concat(extensions, renegotiation_extension);

        cipherSuite = new CipherSuite(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA);
        ServerHello sh = new ServerHello(currentTLS.protocolVersion, server_random, new byte[]{}, CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA, (byte) 0x00, extensions);
        sendHandshakeMessage(sh);

        return receiveMessages();
    }

    public String sendServerHelloDHE() throws Exception {
        // Generate server_random
        rand.nextBytes(server_random);

        byte[] extensions = new byte[]{};

        // Add renegotiation extension (needed for miTLS)
        byte[] renegotiation_extension;

        renegotiation_extension = new byte[]{(byte) 0xFF, 0x01, 0x00, 0x01, 0x00};
        extensions = Utils.concat(extensions, renegotiation_extension);

        cipherSuite = new CipherSuite(CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA);
        ServerHello sh = new ServerHello(currentTLS.protocolVersion, server_random, new byte[]{}, CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA, (byte) 0x00, extensions);
        sendHandshakeMessage(sh);

        return receiveMessages();
    }

    public String sendClientHello(byte[] ciphersuites) throws Exception {
        // Generate client_random
        rand.nextBytes(client_random);

        byte[] extensions = new byte[]{};

        // Add renegotiation extension (needed for miTLS)
        Extension renegotiation_extension = new Extension(TLS.EXTENSION_TYPE_RENEGOGIATION_INFO, new byte[]{0x00});
        extensions = Utils.concat(extensions, renegotiation_extension.getBytes());

        // Add heartbeat extension
        if (ENABLE_HEARTBEAT) {
            Extension heartbeat_extension = new Extension(TLS.EXTENSION_TYPE_HEARTBEAT, new byte[]{0x02});
            extensions = Utils.concat(extensions, heartbeat_extension.getBytes());
        }

        ClientHello ch = new ClientHello(currentTLS.getProtocolVersion(), client_random, new byte[]{}, ciphersuites, new byte[]{0x00}, extensions);

        if (OPENSSL_MODE) {
            // Reset buffer containing all handshake messages
            handshakeMessages = new byte[]{};
        }

        if (DEBUG) log.debug("ClientHello contents: " + Utils.bytesToHexString(ch.getBytes()));
        sendHandshakeMessage(ch);

        return receiveMessages();
    }

    public String sendClientHelloAll() throws Exception {
        return sendClientHello(Utils.concat(Utils.concat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA, CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA), Utils.concat(CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA, CipherSuite.TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA)));
    }

    public String sendClientHelloRSA() throws Exception {
        return sendClientHello(Utils.concat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA, CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA));
    }

    public String sendClientHelloDHE() throws Exception {
        return sendClientHello(Utils.concat(CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA, CipherSuite.TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA));
    }

    public String sendClientHello2() throws Exception {
        // Generate client_random
        rand.nextBytes(client_random);

        byte[] extensions = new byte[]{};

        // Add renegotiation extension (needed for miTLS)
        byte[] renegotiation_extension;

        // OpenSSL reusing keys bug
        if (session_id.length > 0) {
            log.debug("");
            renegotiation_extension = new byte[5 + verify_data.length];
            renegotiation_extension[0] = (byte) 0xFF;
            renegotiation_extension[1] = 0x01;
            renegotiation_extension[2] = 0x00;
            renegotiation_extension[3] = (byte) ((0xFF & verify_data.length) + 1);
            renegotiation_extension[4] = (byte) (0xFF & verify_data.length);
            System.arraycopy(verify_data, 0, renegotiation_extension, 5, verify_data.length);
        } else {
            renegotiation_extension = new byte[]{(byte) 0xFF, 0x01, 0x00, 0x01, 0x00};
        }
        extensions = Utils.concat(extensions, renegotiation_extension);

        // Add heartbeat extension
        if (ENABLE_HEARTBEAT) {
            byte[] heartbeat_extension = new byte[]{0x00, 0x0F, 0x00, 0x01, 0x02};
            extensions = Utils.concat(extensions, heartbeat_extension);
        }

        // OpenSSL reusing keys bug
        ClientHello ch = new ClientHello(currentTLS.getProtocolVersion(), client_random, session_id, Utils.concat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA, CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA), new byte[]{0x00}, extensions);
        handshakeMessages = new byte[]{};

        if (OPENSSL_MODE) {
            // Reset buffer containing all handshake messages
            handshakeMessages = new byte[]{};
        }

        if (DEBUG) log.debug("ClientHello contents: " + Utils.bytesToHexString(ch.getBytes()));
        sendHandshakeMessage(ch);

        return receiveMessages();
    }

    public String sendEmptyCertificate() throws Exception {
        Certificate cm = new Certificate(new X509Certificate[]{});
        sendHandshakeMessage(cm);

        return receiveMessages();
    }

    public String sendClientCertificate() throws Exception {
        Certificate cm = new Certificate(new X509Certificate[]{clientCertificate});
        sendHandshakeMessage(cm);

        return receiveMessages();
    }

    public String sendServerCertificate() throws Exception {
        Certificate cm = new Certificate(new X509Certificate[]{serverCertificate});
        sendHandshakeMessage(cm);

        return receiveMessages();
    }

    public String sendCertificateRequest() throws Exception {
        byte[] cert_types = new byte[]{0x01}; // RSA
        byte[] supported_algorithms = Crypto.HASH_SIGNATURE_ALGORITHM_SHA1RSA;
        byte[] distinguished_names = new byte[]{};

        sendHandshakeMessage(new CertificateRequest(cert_types, supported_algorithms, distinguished_names));

        return receiveMessages();
    }

    public String sendClientCertificateVerify() throws IOException, Exception {
        byte[] signature = Crypto.SIGN_RSA_SHA256(clientPrivateKey, handshakeMessages);

        sendHandshakeMessage(new CertificateVerify(Crypto.HASH_SIGNATURE_ALGORITHM_SHA256RSA, signature));

        return receiveMessages();
    }

    public String sendServerKeyExchange() throws Exception {
        ByteArrayOutputStream signData = new ByteArrayOutputStream();

        signData.write(client_random);
        signData.write(server_random);

        byte[] dh_p = dhPrivateKey.getParams().getP().toByteArray();

        int i;
        for (i = 0; i < dh_p.length; i++) {
            if (dh_p[i] != 0x00) break;
        }
        dh_p = Arrays.copyOfRange(dh_p, i, dh_p.length);

        signData.write(Utils.getbytes16(dh_p.length));
        signData.write(dh_p);

        byte[] dh_g = dhPrivateKey.getParams().getG().toByteArray();
        for (i = 0; i < dh_g.length; i++) {
            if (dh_g[i] != 0x00) break;
        }
        dh_g = Arrays.copyOfRange(dh_g, i, dh_g.length);

        signData.write(Utils.getbytes16(dh_g.length));
        signData.write(dh_g);

        byte[] dh_Ys = dhPubKey.getY().toByteArray();
        for (i = 0; i < dh_Ys.length; i++) {
            if (dh_Ys[i] != 0x00) break;
        }
        dh_Ys = Arrays.copyOfRange(dh_Ys, i, dh_Ys.length);

        signData.write(Utils.getbytes16(dh_Ys.length));
        signData.write(dh_Ys);

        byte[] signature = Crypto.SIGN_RSA_SHA256(serverPrivateKey, signData.toByteArray());
        ServerKeyExchange ske = new ServerKeyExchange(dh_p, dh_g, dh_Ys, (int) Crypto.HASH_ALGORITHM_SHA256, (int) Crypto.SIGNATURE_ALGORITHM_RSA, signature);

        sendHandshakeMessage(ske);
        return receiveMessages();
    }

    public String sendServerHelloDone() throws Exception {
        ServerHelloDone shd = new ServerHelloDone();
        sendHandshakeMessage(shd);
        return receiveMessages();
    }

    public String sendClientKeyExchange() throws Exception {
        ClientKeyExchange cke;

        switch (cipherSuite.keyExchange) {
            case CipherSuite.ALG_RSA:
                byte[] preMasterSecret;
                byte[] encryptedPreMaster;

                preMasterSecret = new byte[48];
                rand.nextBytes(preMasterSecret);
                preMasterSecret[0] = currentTLS.getProtocolVersion().getMajorVersion();
                preMasterSecret[1] = currentTLS.getProtocolVersion().getMinorVersion();

                Cipher cipher = cipherSuite.keyExchangeCipher;
                cipher.init(Cipher.WRAP_MODE, serverKey, rand);
                encryptedPreMaster = cipher.wrap(new SecretKeySpec(preMasterSecret, ""));

                master_secret = currentTLS.masterSecret(preMasterSecret, server_random, client_random);

                cke = new ClientKeyExchange(encryptedPreMaster);
                break;

            case CipherSuite.ALG_DHE_RSA:
                // Implicit DH (i.e. Yc included in client certificate, send empty message)
                // NOT YET IMPLEMENTED

                // Explicit DH
                cke = new ClientKeyExchange(dhPubKey.getY().toByteArray());
                break;

            default:
                throw new Exception("Unknown key exchange algorithm: "
                        + cipherSuite.keyExchange);
        }

        sendHandshakeMessage(cke);

        return receiveMessages();
    }

    public String sendChangeCipherSpec() throws Exception {
        sendMessage(TLS.CONTENT_TYPE_CCS, new byte[]{0x01});

        if (CLIENT_MODE) setCiphersClient();
        else setCiphersServer();

		/*
		if(OPENSSL_MODE) {
			if(CLIENT_MODE) {
				setCiphersServer();
				verify_data = currentTLS.verifyDataClient(master_secret, handshakeMessages);
			}
			else {
				setCiphersClient();
				verify_data = currentTLS.verifyDataServer(master_secret, handshakeMessages);
			} 
		}
		*/

        ccs_out = true;

        return receiveMessages();
    }

    // Used for OpenSSL key re-use bug
    public String sendChangeCipherSpec2() throws Exception {
        sendMessage(TLS.CONTENT_TYPE_CCS, new byte[]{0x01});

        //master_secret = new byte[] {};
        //Arrays.fill(client_random, (byte) 0x00);
        //Arrays.fill(server_random, (byte) 0x00);

        CLIENT_MODE = false;
        //setCiphersClient();
        setCiphersServer();
        CLIENT_MODE = true;
        //writeMACSeqNr = tmpSeqNr;
		
		/*
		if(OPENSSL_MODE) {
			if(CLIENT_MODE) {
				setCiphersServer();
				verify_data = currentTLS.verifyDataClient(master_secret, handshakeMessages);
			}
			else {
				setCiphersClient();
				verify_data = currentTLS.verifyDataServer(master_secret, handshakeMessages);
			} 
		}
		*/

        ccs_out = true;

        return receiveMessages();
    }

    public String sendChangeCipherSpec3() throws Exception {
        sendMessage(TLS.CONTENT_TYPE_CCS, new byte[]{0x01});

        //master_secret = new byte[] {};
        //Arrays.fill(client_random, (byte) 0x00);
        //Arrays.fill(server_random, (byte) 0x00);

        ccs_out = true;

        return receiveMessages();
    }

    public String sendAlert10() throws Exception {
        Alert alert = new Alert((byte) 1, (byte) 0);

        sendMessage(TLS.CONTENT_TYPE_ALERT, alert.getBytes());

        return receiveMessages();
    }

    public String sendAlert1100() throws Exception {
        Alert alert = new Alert((byte) 1, (byte) 100);

        sendMessage(TLS.CONTENT_TYPE_ALERT, alert.getBytes());

        return receiveMessages();
    }

    public String sendAlert1255() throws Exception {
        Alert alert = new Alert((byte) 1, (byte) 255);

        sendMessage(TLS.CONTENT_TYPE_ALERT, alert.getBytes());

        return receiveMessages();
    }

    public String sendFinished() throws Exception {
        if (DEBUG) {
            log.debug("master_secret: " + Utils.bytesToHexString(master_secret));
            log.debug("verify_data input: " + Utils.bytesToHexString(handshakeMessages));
        }

        if (CLIENT_MODE) verify_data = currentTLS.verifyDataClient(master_secret, handshakeMessages);
        else verify_data = currentTLS.verifyDataServer(master_secret, handshakeMessages);

        Finished finished = new Finished(verify_data);
        sendHandshakeMessage(finished);

        return receiveMessages();
    }

    public String sendApplicationData() throws Exception {
        String req = "";
        if (CLIENT_MODE) req = "GET / HTTP/1.0\n\n";
        else req = "HTTP/1.1 200 OK\nAccess-Control-Allow-Origin: *\n\ntest";
        sendMessage(TLS.CONTENT_TYPE_APPLICATION, req.getBytes());

        return receiveMessages();
    }

    public String sendApplicationDataEmpty() throws Exception {
        sendMessage(TLS.CONTENT_TYPE_APPLICATION, new byte[]{});
        return receiveMessages();
    }

    public void close() {
        if (targetProcess != null) {
            targetProcess.destroy();
        }
    }

    public String processSymbol(String input) throws Exception {

        if (!socket.isConnected() || socket.isClosed()) return "ConnectionClosed";

        try {
            switch (input) {
                case "ClientHello":
                    return sendClientHelloAll();
                case "ClientHelloDHE":
                    return sendClientHelloDHE();
                case "ClientHelloRSA":
                    return sendClientHelloRSA();
                case "ServerHelloRSA":
                    return sendServerHelloRSA();
                case "ServerHelloDHE":
                    return sendServerHelloDHE();
                case "EmptyCertificate":
                    return sendEmptyCertificate();
                case "ServerCertificate":
                    return sendServerCertificate();
                case "ServerKeyExchange":
                    return sendServerKeyExchange();
                case "CertificateRequest":
                    return sendCertificateRequest();
                case "ServerHelloDone":
                    return sendServerHelloDone();
                case "ClientCertificate":
                    return sendClientCertificate();
                case "ClientCertificateVerify":
                    return sendClientCertificateVerify();
                case "ClientKeyExchange":
                    return sendClientKeyExchange();
                case "ChangeCipherSpec":
                    return sendChangeCipherSpec();
                case "Finished":
                    return sendFinished();
                case "ApplicationData":
                    return sendApplicationData();
                case "ApplicationDataEmpty":
                    return sendApplicationDataEmpty();
                case "HeartbeatRequest":
                    return sendHeartbeatRequest();
                case "HeartbeatResponse":
                    return sendHeartbeatResponse();
                case "Alert10":
                    return sendAlert10();
                case "Alert1100":
                    return sendAlert1100();
                default:
                    log.debug("Unknown input symbol (" + input + ")...");
                    System.exit(1);
            }
        } catch (SocketException e) {
            //String outAction = "ConnectionClosedException";
            return "ConnectionClosed";
        }
        return null;
    }

    public void loadServerKey() throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException, UnrecoverableKeyException, NoSuchProviderException, InvalidAlgorithmParameterException, InvalidKeySpecException {
        String keystoreFilename = "keys/keystore";

        char[] password = "changeit".toCharArray();

        FileInputStream fIn = new FileInputStream(keystoreFilename);
        KeyStore keystore = KeyStore.getInstance("JKS");

        keystore.load(fIn, password);
        serverCertificate = (X509Certificate) keystore.getCertificate("server");
        serverPrivateKey = (PrivateKey) keystore.getKey("server", password);

        // Generate DH keys for this session
        // Use hardcoded DH parameters
        DHParameterSpec dhParams = new DHParameterSpec(new BigInteger(new byte[]{(byte) 0x00, (byte) 0xad, (byte) 0x77, (byte) 0xcd, (byte) 0xb7, (byte) 0x14, (byte) 0x6f, (byte) 0xfe, (byte) 0x08, (byte) 0x1a, (byte) 0xee, (byte) 0xd2, (byte) 0x2c, (byte) 0x18, (byte) 0x29, (byte) 0x62, (byte) 0x5a, (byte) 0xff, (byte) 0x03, (byte) 0x5d, (byte) 0xde, (byte) 0xba, (byte) 0x0d, (byte) 0xd4, (byte) 0x36, (byte) 0x15, (byte) 0x03, (byte) 0x11, (byte) 0x21, (byte) 0x48, (byte) 0xd9, (byte) 0x77, (byte) 0xfb, (byte) 0x67, (byte) 0xb0, (byte) 0x74, (byte) 0x2e, (byte) 0x68, (byte) 0xed, (byte) 0x5a, (byte) 0x3f, (byte) 0x8a, (byte) 0x3e, (byte) 0xdb, (byte) 0x81, (byte) 0xa3, (byte) 0x3b, (byte) 0xaf, (byte) 0x26, (byte) 0xe4, (byte) 0x54, (byte) 0x00, (byte) 0x85, (byte) 0x0d, (byte) 0xfd, (byte) 0x23, (byte) 0x21, (byte) 0xc1, (byte) 0xfe, (byte) 0x69, (byte) 0xe4, (byte) 0xf3, (byte) 0x57, (byte) 0xe6, (byte) 0x0a, (byte) 0x7c, (byte) 0x62, (byte) 0xc0, (byte) 0xd6, (byte) 0x40, (byte) 0x3e, (byte) 0x94, (byte) 0x9e, (byte) 0x49, (byte) 0x72, (byte) 0x5a, (byte) 0x21, (byte) 0x53, (byte) 0xb0, (byte) 0x83, (byte) 0x05, (byte) 0x81, (byte) 0x5a, (byte) 0xde, (byte) 0x17, (byte) 0x31, (byte) 0xbf, (byte) 0xa8, (byte) 0xa9, (byte) 0xe5, (byte) 0x28, (byte) 0x1a, (byte) 0xfc, (byte) 0x06, (byte) 0x1e, (byte) 0x49, (byte) 0xfe, (byte) 0xdc, (byte) 0x08, (byte) 0xe3, (byte) 0x29, (byte) 0xfe, (byte) 0x5b, (byte) 0x88, (byte) 0x66, (byte) 0x39, (byte) 0xa8, (byte) 0x69, (byte) 0x62, (byte) 0x88, (byte) 0x47, (byte) 0x36, (byte) 0xf5, (byte) 0xdd, (byte) 0x92, (byte) 0x8f, (byte) 0xca, (byte) 0x32, (byte) 0x4b, (byte) 0x87, (byte) 0xad, (byte) 0xbf, (byte) 0xab, (byte) 0x4a, (byte) 0x9d, (byte) 0xd5, (byte) 0xb8, (byte) 0x2c, (byte) 0xc4, (byte) 0x43, (byte) 0xb2, (byte) 0x21, (byte) 0xb4, (byte) 0x2a, (byte) 0x9b, (byte) 0x42, (byte) 0x17, (byte) 0x6d, (byte) 0xb6, (byte) 0x86, (byte) 0x42, (byte) 0x41, (byte) 0xb1, (byte) 0xc7, (byte) 0x37, (byte) 0x37, (byte) 0x95, (byte) 0x6d, (byte) 0x62, (byte) 0xca, (byte) 0xa6, (byte) 0x57, (byte) 0x33, (byte) 0x88, (byte) 0xe2, (byte) 0x31, (byte) 0xfe, (byte) 0xd1, (byte) 0x51, (byte) 0xe7, (byte) 0x73, (byte) 0xae, (byte) 0x3c, (byte) 0xa7, (byte) 0x4b, (byte) 0xbc, (byte) 0x8a, (byte) 0x3d, (byte) 0xc5, (byte) 0x9a, (byte) 0x28, (byte) 0x9a, (byte) 0xf9, (byte) 0x57, (byte) 0xb6, (byte) 0xec, (byte) 0xf6, (byte) 0x75, (byte) 0xaa, (byte) 0x56, (byte) 0xc1, (byte) 0x42, (byte) 0x9f, (byte) 0x6a, (byte) 0x7c, (byte) 0x91, (byte) 0x8b, (byte) 0x5e, (byte) 0xea, (byte) 0x54, (byte) 0x32, (byte) 0x90, (byte) 0x8a, (byte) 0x9d, (byte) 0x76, (byte) 0x2a, (byte) 0x29, (byte) 0x1b, (byte) 0x84, (byte) 0x35, (byte) 0xe6, (byte) 0x21, (byte) 0x07, (byte) 0xb2, (byte) 0xcb, (byte) 0x5c, (byte) 0xf9, (byte) 0x5b, (byte) 0xe9, (byte) 0x5e, (byte) 0x1b, (byte) 0x80, (byte) 0xd5, (byte) 0x53, (byte) 0xd7, (byte) 0xa4, (byte) 0x26, (byte) 0x58, (byte) 0xe4, (byte) 0xe9, (byte) 0x3f, (byte) 0xfd, (byte) 0xeb, (byte) 0x78, (byte) 0xf2, (byte) 0x25, (byte) 0x02, (byte) 0x42, (byte) 0xf8, (byte) 0x50, (byte) 0x13, (byte) 0xbb, (byte) 0x01, (byte) 0x39, (byte) 0xf3, (byte) 0xcf, (byte) 0x5c, (byte) 0x51, (byte) 0xdf, (byte) 0xed, (byte) 0xc5, (byte) 0xfa, (byte) 0xd8, (byte) 0x4f, (byte) 0xae, (byte) 0x76, (byte) 0xe8, (byte) 0x30, (byte) 0xfc, (byte) 0x85, (byte) 0xaa, (byte) 0x8c, (byte) 0x91, (byte) 0x02, (byte) 0x2b, (byte) 0x61, (byte) 0x87
        }), new BigInteger(new byte[]{0x05}));

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DiffieHellman");
        keyPairGenerator.initialize(dhParams);

        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        dhPubKey = (DHPublicKey) keyPair.getPublic();
        dhPrivateKey = (DHPrivateKey) keyPair.getPrivate();
    }

    public void loadClientKey() throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException, UnrecoverableKeyException, InvalidAlgorithmParameterException {
        String keystoreFilename = "keys/keystore";

        char[] password = "changeit".toCharArray();

        FileInputStream fIn = new FileInputStream(keystoreFilename);
        KeyStore keystore = KeyStore.getInstance("JKS");

        keystore.load(fIn, password);
        clientCertificate = (X509Certificate) keystore.getCertificate("client");
        clientPrivateKey = (PrivateKey) keystore.getKey("client", password);

        // Generate DH keys for this session
        // Use hardcoded DH parameters
        DHParameterSpec dhParams = new DHParameterSpec(new BigInteger(new byte[]{(byte) 0x00, (byte) 0xad, (byte) 0x77, (byte) 0xcd, (byte) 0xb7, (byte) 0x14, (byte) 0x6f, (byte) 0xfe, (byte) 0x08, (byte) 0x1a, (byte) 0xee, (byte) 0xd2, (byte) 0x2c, (byte) 0x18, (byte) 0x29, (byte) 0x62, (byte) 0x5a, (byte) 0xff, (byte) 0x03, (byte) 0x5d, (byte) 0xde, (byte) 0xba, (byte) 0x0d, (byte) 0xd4, (byte) 0x36, (byte) 0x15, (byte) 0x03, (byte) 0x11, (byte) 0x21, (byte) 0x48, (byte) 0xd9, (byte) 0x77, (byte) 0xfb, (byte) 0x67, (byte) 0xb0, (byte) 0x74, (byte) 0x2e, (byte) 0x68, (byte) 0xed, (byte) 0x5a, (byte) 0x3f, (byte) 0x8a, (byte) 0x3e, (byte) 0xdb, (byte) 0x81, (byte) 0xa3, (byte) 0x3b, (byte) 0xaf, (byte) 0x26, (byte) 0xe4, (byte) 0x54, (byte) 0x00, (byte) 0x85, (byte) 0x0d, (byte) 0xfd, (byte) 0x23, (byte) 0x21, (byte) 0xc1, (byte) 0xfe, (byte) 0x69, (byte) 0xe4, (byte) 0xf3, (byte) 0x57, (byte) 0xe6, (byte) 0x0a, (byte) 0x7c, (byte) 0x62, (byte) 0xc0, (byte) 0xd6, (byte) 0x40, (byte) 0x3e, (byte) 0x94, (byte) 0x9e, (byte) 0x49, (byte) 0x72, (byte) 0x5a, (byte) 0x21, (byte) 0x53, (byte) 0xb0, (byte) 0x83, (byte) 0x05, (byte) 0x81, (byte) 0x5a, (byte) 0xde, (byte) 0x17, (byte) 0x31, (byte) 0xbf, (byte) 0xa8, (byte) 0xa9, (byte) 0xe5, (byte) 0x28, (byte) 0x1a, (byte) 0xfc, (byte) 0x06, (byte) 0x1e, (byte) 0x49, (byte) 0xfe, (byte) 0xdc, (byte) 0x08, (byte) 0xe3, (byte) 0x29, (byte) 0xfe, (byte) 0x5b, (byte) 0x88, (byte) 0x66, (byte) 0x39, (byte) 0xa8, (byte) 0x69, (byte) 0x62, (byte) 0x88, (byte) 0x47, (byte) 0x36, (byte) 0xf5, (byte) 0xdd, (byte) 0x92, (byte) 0x8f, (byte) 0xca, (byte) 0x32, (byte) 0x4b, (byte) 0x87, (byte) 0xad, (byte) 0xbf, (byte) 0xab, (byte) 0x4a, (byte) 0x9d, (byte) 0xd5, (byte) 0xb8, (byte) 0x2c, (byte) 0xc4, (byte) 0x43, (byte) 0xb2, (byte) 0x21, (byte) 0xb4, (byte) 0x2a, (byte) 0x9b, (byte) 0x42, (byte) 0x17, (byte) 0x6d, (byte) 0xb6, (byte) 0x86, (byte) 0x42, (byte) 0x41, (byte) 0xb1, (byte) 0xc7, (byte) 0x37, (byte) 0x37, (byte) 0x95, (byte) 0x6d, (byte) 0x62, (byte) 0xca, (byte) 0xa6, (byte) 0x57, (byte) 0x33, (byte) 0x88, (byte) 0xe2, (byte) 0x31, (byte) 0xfe, (byte) 0xd1, (byte) 0x51, (byte) 0xe7, (byte) 0x73, (byte) 0xae, (byte) 0x3c, (byte) 0xa7, (byte) 0x4b, (byte) 0xbc, (byte) 0x8a, (byte) 0x3d, (byte) 0xc5, (byte) 0x9a, (byte) 0x28, (byte) 0x9a, (byte) 0xf9, (byte) 0x57, (byte) 0xb6, (byte) 0xec, (byte) 0xf6, (byte) 0x75, (byte) 0xaa, (byte) 0x56, (byte) 0xc1, (byte) 0x42, (byte) 0x9f, (byte) 0x6a, (byte) 0x7c, (byte) 0x91, (byte) 0x8b, (byte) 0x5e, (byte) 0xea, (byte) 0x54, (byte) 0x32, (byte) 0x90, (byte) 0x8a, (byte) 0x9d, (byte) 0x76, (byte) 0x2a, (byte) 0x29, (byte) 0x1b, (byte) 0x84, (byte) 0x35, (byte) 0xe6, (byte) 0x21, (byte) 0x07, (byte) 0xb2, (byte) 0xcb, (byte) 0x5c, (byte) 0xf9, (byte) 0x5b, (byte) 0xe9, (byte) 0x5e, (byte) 0x1b, (byte) 0x80, (byte) 0xd5, (byte) 0x53, (byte) 0xd7, (byte) 0xa4, (byte) 0x26, (byte) 0x58, (byte) 0xe4, (byte) 0xe9, (byte) 0x3f, (byte) 0xfd, (byte) 0xeb, (byte) 0x78, (byte) 0xf2, (byte) 0x25, (byte) 0x02, (byte) 0x42, (byte) 0xf8, (byte) 0x50, (byte) 0x13, (byte) 0xbb, (byte) 0x01, (byte) 0x39, (byte) 0xf3, (byte) 0xcf, (byte) 0x5c, (byte) 0x51, (byte) 0xdf, (byte) 0xed, (byte) 0xc5, (byte) 0xfa, (byte) 0xd8, (byte) 0x4f, (byte) 0xae, (byte) 0x76, (byte) 0xe8, (byte) 0x30, (byte) 0xfc, (byte) 0x85, (byte) 0xaa, (byte) 0x8c, (byte) 0x91, (byte) 0x02, (byte) 0x2b, (byte) 0x61, (byte) 0x87
        }), new BigInteger(new byte[]{0x05}));

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DiffieHellman");
        keyPairGenerator.initialize(dhParams);

        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        dhPubKey = (DHPublicKey) keyPair.getPublic();
        dhPrivateKey = (DHPrivateKey) keyPair.getPrivate();
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 2) {
            TLSTestService tls = new TLSTestService();
            tls.setTarget("server");
            tls.setHost(args[1]);
            tls.setPort(new Integer(args[0]));
            tls.setReceiveMessagesTimeout(100);

            tls.start();

            tls.useTLS10();

            try {
                System.out.print(tls.sendClientHelloRSA());
                //log.debug("ClientHelloDHE: " + tls.sendClientHelloDHE());

                if (args.length >= 3 && args[2].equals("1")) {
                    System.out.print(" " + tls.sendEmptyCertificate());
                    //log.debug("ClientCertificate: " + tls.sendClientCertificate());
                }

                System.out.print(" " + tls.sendClientKeyExchange());
                System.out.print(" " + tls.sendChangeCipherSpec());
                System.out.print(" " + tls.sendFinished());
                log.debug(" " + tls.sendApplicationData());
            } catch (SocketException e) {
                log.debug(" ConnectionClosed");
            }

            tls.closeSocket();
            tls.close();
        }
    }

}
