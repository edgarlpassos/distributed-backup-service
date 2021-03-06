package server.communication;


import server.chord.Node;
import server.chord.NodeInfo;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.ExecutorService;


public class Connection {
    private final SSLSocket socket;
    private final ObjectOutputStream objectOutputStream;
    private final ObjectInputStream objectInputStream;
    private NodeInfo destination;

    Connection(NodeInfo destination) throws IOException {
        this.destination = destination;
        socket = (SSLSocket) SSLSocketFactory.getDefault().
                createSocket(destination.getAddress(), destination.getPort());

        socket.setTcpNoDelay(true);
        objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
        objectInputStream = new ObjectInputStream(socket.getInputStream());
    }

    Connection(SSLSocket socket, Node currentNode, ExecutorService connectionsThreadPool) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
        objectInputStream = new ObjectInputStream(socket.getInputStream());
        waitForAuthentication(currentNode);

    }

    /**
     * Checks if the socket is open.
     *
     * @return
     */
    boolean isOpen() {
        return !socket.isClosed();
    }

    /**
     * Sends the given operation.
     *
     * @param operation
     * @throws IOException
     */
    public void sendOperation(Operation operation) throws IOException {
        try {
            synchronized (objectOutputStream) {
                objectOutputStream.reset();
                objectOutputStream.writeObject(operation);
                objectOutputStream.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Waits for confirmation that the node is authentic.
     *
     * @param self
     */
    private void waitForAuthentication(Node self) {
        try {
            Operation operation;
            operation = ((Operation) objectInputStream.readObject());
            this.destination = operation.getOrigin();
            Mailman.addOpenConnection(this);
            operation.run(self);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
            closeConnection();
        }
    }

    /**
     *
     * Listen to other nodes.
     *
     * @param self
     */
    void listen(Node self) {
        while (true) {
            try {
                ((Operation) objectInputStream.readObject()).run(self);
            } catch (ClassNotFoundException ignored) {
            } catch (IOException e) {
                closeConnection();
                return;
            }
        }
    }

    /**
     * Close Connection of the socket.
     *
     */
    void closeConnection() {
        if (destination != null)
            Mailman.connectionClosed(destination);

        try {
            objectInputStream.close();
            objectOutputStream.close();
            socket.close();
        } catch (IOException e) {
            System.err.println("Unable to close socket");
        }
    }

    NodeInfo getNodeInfo() {
        return destination;
    }
}
