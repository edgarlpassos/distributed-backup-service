package server.communication.operations;

import server.chord.Node;
import server.chord.NodeInfo;
import server.communication.Operation;

public class NotifyOperation extends Operation {

    public NotifyOperation(NodeInfo origin) {
        super(origin);
    }

    @Override
    public void run(Node currentNode) {
        if (currentNode.updatePredecessor(origin))
            System.out.println("Notified of new predecessor.\nSetting predecessor to " + origin.getId());
    }

    public String getKey() {
        return null;
    }
}
