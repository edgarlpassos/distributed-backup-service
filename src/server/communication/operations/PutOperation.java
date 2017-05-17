package server.communication.operations;

import server.chord.Node;
import server.chord.NodeInfo;
import server.communication.Mailman;

import java.io.IOException;
import java.math.BigInteger;

public class PutOperation implements Operation {
    private final BigInteger key;
    private final byte[] value;
    private final NodeInfo origin;

    public PutOperation(NodeInfo origin, BigInteger key, byte[] value) {
        this.origin = origin;
        this.key = key;
        this.value = value;
    }

    @Override
    public void run(Node currentNode) {
        PutResultOperation result = new PutResultOperation(origin, key, currentNode.store(key, value));

        try {
            Mailman.sendOperation(origin, result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}