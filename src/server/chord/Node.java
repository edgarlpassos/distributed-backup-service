package server.chord;

import server.communication.Mailman;
import server.communication.Operation;
import server.communication.OperationManager;
import server.communication.operations.*;
import server.exceptions.KeyNotFoundException;

import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.*;

import static server.chord.DistributedHashTable.OPERATION_TIMEOUT;
import static server.chord.FingerTable.LOOKUP_TIMEOUT;

public class Node {
    public static final int MAX_NODES = 128;
    private static final int REPLICATION_DEGREE = 3;

    private final NodeInfo self;
    private final FingerTable fingerTable;
    private final DistributedHashTable dht;
    private CompletableFuture<NodeInfo> ongoingPredecessorLookup;

    public final OperationManager<Integer, Boolean> ongoingKeySendings = new OperationManager<>();

    public final OperationManager<BigInteger, Boolean> ongoingDeletes = new OperationManager<>();
    public final OperationManager<BigInteger, Boolean> ongoingInsertions = new OperationManager<>();
    public final OperationManager<BigInteger, byte[]> ongoingGets = new OperationManager<>();

    private final ConcurrentHashMap<Integer, ConcurrentHashMap<BigInteger, byte[]>> replicatedValues = new ConcurrentHashMap<>();
    private final ExecutorService threadPool = Executors.newFixedThreadPool(10);
    private final ScheduledExecutorService stabilizationExecutor = Executors.newScheduledThreadPool(5);
    private ConcurrentHashMap<BigInteger, Integer> unfinishedReplications = new ConcurrentHashMap<>();

    /**
     * @param address Address of this server
     * @param port    Port to start the service in
     */
    public Node(InetAddress address, int port) throws IOException, NoSuchAlgorithmException {
        self = new NodeInfo(address, port);
        fingerTable = new FingerTable(self);
        ongoingPredecessorLookup = null;
        dht = new DistributedHashTable(this);
    }

    private CompletableFuture<NodeInfo> requestSuccessorPredecessor(NodeInfo successor) throws Exception {
        /* Check if the request is already being made */

        CompletableFuture<NodeInfo> requestResult = ongoingPredecessorLookup;
        if (requestResult != null)
            return requestResult;

        requestResult = new CompletableFuture<>();
        ongoingPredecessorLookup = requestResult;
        Mailman.sendOperation(successor, new RequestPredecessorOperation(self));

        return requestResult;
    }

    public NodeInfo getInfo() {
        return self;
    }

    public boolean keyBelongsToSuccessor(BigInteger key) {
        return fingerTable.keyBelongsToSuccessor(key);
    }

    /**
     * Add the node to the network and update its finger table.
     *
     * @param bootstrapperNode Node that will provide the information with which our finger table will be updated.
     */
    public boolean bootstrap(NodeInfo bootstrapperNode) {
        /* Get the node's successors */
        if (!fingerTable.findSuccessors(bootstrapperNode))
            return false;

        /*
         * The node now has knowledge of its next r successors,
         * now the finger table will be filled using the successor
         */
        fingerTable.fill();

        NodeInfo successor = fingerTable.getSuccessor();

        /* Get the successor's predecessor, which will be the new node's predecessor */
        CompletableFuture<Void> getPredecessor;
        try {
            getPredecessor = requestSuccessorPredecessor(successor).thenAcceptAsync(fingerTable::updatePredecessor, threadPool);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        try {
            getPredecessor.get();
        } catch (CancellationException | ExecutionException | InterruptedException e) {
            return false;
        }

        return true;
    }

    /**
     * Search the finger table for the next best node
     *
     * @param key key that is being searched
     * @return NodeInfo for the closest preceding node to the searched key
     */
    public NodeInfo getNextBestNode(BigInteger key) {
        return fingerTable.getNextBestNode(key);
    }

    /**
     * Get the node's successor (finger table's first entry)
     *
     * @return NodeInfo for the node's successor
     */
    public NodeInfo getSuccessor() {
        return fingerTable.getSuccessor();
    }

    /**
     * Get the node's predecessor
     *
     * @return NodeInfo for the node's predecessor
     */
    public NodeInfo getPredecessor() {
        return fingerTable.getPredecessor();
    }

    public void finishPredecessorRequest(NodeInfo predecessor) {
        fingerTable.updatePredecessor(predecessor);

        if (ongoingPredecessorLookup == null)
            System.out.println("Null");
        ongoingPredecessorLookup.complete(predecessor);
        ongoingPredecessorLookup = null;
    }

    public void initializeStabilization() {
        stabilizationExecutor.scheduleWithFixedDelay(this::stabilizationProtocol, 5, 5, TimeUnit.SECONDS);

    }

    private void stabilizationProtocol() {
        fingerTable.stabilizationProtocol();
        updateOwnKeysReplication();
        checkReplicasOwners();
    }

    /**
     * Checks if the replica owner is alive and syncs the replicas with it.
     * If it is not alive, then insert all of its keys in the network.
     */
    private void checkReplicasOwners() {
        for (Map.Entry<Integer, ConcurrentHashMap<BigInteger, byte[]>> entry : replicatedValues.entrySet()) {
            int attempts = 3;
            while (attempts > 0) {
                try {
                    NodeInfo owner = fingerTable.lookup(BigInteger.valueOf(entry.getKey())).get(LOOKUP_TIMEOUT, TimeUnit.MILLISECONDS);

                    Mailman.sendOperation(
                            owner,
                            new ReplicationSyncOperation(
                                    self,
                                    new HashSet<>(Collections.list(entry.getValue().keys()))));

                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    e.printStackTrace();
                    System.out.println("checkReplicasOwners:: error on get.");
                    attempts--;
                } catch (Exception e) {
                    e.printStackTrace();
                    entry.getValue().forEach(this::insert);
                }
            }
        }
    }

    /**
     * This function is run periodically and ensures that the replication is
     * at least REPLICATION_DEGREE, if there are at least REPLICATION_DEGREE nodes
     * in the network.
     */
    private void updateOwnKeysReplication() {
        for (Map.Entry<BigInteger, Integer> entry : unfinishedReplications.entrySet()) {
            for (int i = entry.getValue(); i < REPLICATION_DEGREE; i++)
                ensureReplication(entry.getKey(), dht.getLocalValue(entry.getKey()));
        }
    }

    public boolean storeKey(BigInteger key, byte[] value) {
        boolean result = dht.storeKey(key, value);
        ensureReplication(key, value);
        return result;
    }

    private void ensureReplication(BigInteger key, byte[] value) {
        NodeInfo nthSuccessor;
        for (int i = unfinishedReplications.getOrDefault(key, 1); i < REPLICATION_DEGREE; i++) {
            try {
                nthSuccessor = fingerTable.getNthSuccessor(i - 1);
            } catch (IndexOutOfBoundsException e) {
                System.err.println("Replication of file with key " + DatatypeConverter.printHexBinary(key.toByteArray()) + " failed.\n" +
                        "Current replication degree is " + i + ".");
                unfinishedReplications.put(key, i);
                return;
            }

            try {
                Mailman.sendOperation(nthSuccessor, new ReplicationOperation(self, key, value));
            } catch (Exception e) {
                informAboutFailure(nthSuccessor);
                i--;
            }
        }
    }

    public void storeReplica(NodeInfo node, BigInteger key, byte[] value) {
        ConcurrentHashMap<BigInteger, byte[]> replicas = replicatedValues.getOrDefault(node.getId(), new ConcurrentHashMap<>());
        replicas.put(key, value);
        replicatedValues.putIfAbsent(node.getId(), replicas);
    }

    public byte[] getLocalValue(BigInteger key) {
        return dht.getLocalValue(key);
    }

    public DistributedHashTable getDistributedHashTable() {
        return dht;
    }

    CompletableFuture<Boolean> insert(BigInteger key, byte[] value) {
        return operation(ongoingInsertions, new InsertOperation(self, key, value), key);
    }

    private CompletableFuture<Boolean> sendKeysToNode(NodeInfo destination, ConcurrentHashMap<BigInteger, byte[]> keys) throws Exception {
        int destinationId = destination.getId();
        CompletableFuture<Boolean> sending = ongoingKeySendings.putIfAbsent(destinationId);

        if (sending != null)
            return sending;

        Mailman.sendOperation(destination, new SendKeysOperation(self, keys));

        return ongoingKeySendings.get(destinationId);
    }

    public boolean updatePredecessor(NodeInfo newPredecessor) {
        if (fingerTable.updatePredecessor(newPredecessor)) {

            try {
                sendKeysToNode(newPredecessor,
                        dht.getKeysBelongingTo(newPredecessor)).get(OPERATION_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                e.printStackTrace();
                informAboutFailure(newPredecessor);
                return false;
            }

            return true;
        } else {
            return false;
        }
    }

    public void informAboutExistence(NodeInfo node) {
        fingerTable.updatePredecessor(node);
        fingerTable.updateSuccessors(node);
        fingerTable.updateFingerTable(node);
    }

    public void informAboutFailure(NodeInfo node) {
        if (node.equals(self)) {
            try {
                throw new Exception("Trying to inform about self.");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }
        System.err.println("Node with ID " + node.getId() + " has failed.");
        Mailman.state();
        NodeInfo predecessor = fingerTable.getPredecessor();

        fingerTable.informSuccessorsOfFailure(node);
        fingerTable.informFingersOfFailure(node);
        fingerTable.informPredecessorOfFailure(node);

        if (predecessor.equals(node)) {
            ConcurrentHashMap<BigInteger, byte[]> replicas = replicatedValues.remove(node.getId());
            if (replicas == null)
                return;

            dht.storeKeys(replicas);
            System.err.println("Node " + node.getId() + " failed. Replicating keys to " + fingerTable.getNthSuccessor(REPLICATION_DEGREE - 2).getId());
            replicateTo(replicas, fingerTable.getNthSuccessor(REPLICATION_DEGREE - 2));
        }
    }

    private void replicateTo(ConcurrentHashMap<BigInteger, byte[]> replicas, NodeInfo node) {
        for (Map.Entry<BigInteger, byte[]> entry : replicas.entrySet()) {
            try {
                Mailman.sendOperation(node, new ReplicationOperation(self, entry.getKey(), entry.getValue()));
            } catch (Exception e) {
                informAboutFailure(node);
                return;
            }
        }
    }

    public void onLookupFinished(BigInteger key, NodeInfo targetNode) {
        informAboutExistence(targetNode);
        fingerTable.ongoingLookups.operationFinished(key, targetNode);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(fingerTable.toString());

        sb.append("\n\nReplicated keys:\n");
        sb.append("NodeID\t\tKey\n");
        replicatedValues.forEach((nodeId, keys) -> keys.forEach((key, value) -> {
            sb.append(nodeId);
            sb.append("\t\t\t\t\t");
            sb.append(DatatypeConverter.printHexBinary(key.toByteArray()));
            sb.append("\n");
        }));

        return sb.toString();
    }

    public void storeSuccessorKeys(ConcurrentHashMap<BigInteger, byte[]> keys) {
        dht.storeKeys(keys);
    }

    CompletableFuture<byte[]> get(BigInteger key) {
        return operation(ongoingGets, new GetOperation(self, key), key);
    }

    private <R> CompletableFuture<R> operation(OperationManager<BigInteger, R> operationManager, Operation
            operation, BigInteger key) {
        CompletableFuture<R> operationState = operationManager.putIfAbsent(key);

        if (operationState != null)
            return operationState;

        operationState = operationManager.get(key);

        NodeInfo destination;
        try {
            destination = fingerTable.lookup(key).get();
        } catch (InterruptedException | ExecutionException e) {
            fingerTable.ongoingLookups.operationFailed(key, new KeyNotFoundException());
            operationManager.operationFailed(key, e);
            return operationState;
        }

        try {
            Mailman.sendOperation(destination, operation);
        } catch (Exception e) {
            operationManager.operationFailed(key, e);
            return operationState;
        }

        return operationState;
    }

    CompletableFuture<Boolean> delete(BigInteger key) {
        return operation(ongoingDeletes, new DeleteOperation(self, key), key);
    }

    public boolean removeValue(BigInteger key) {
        //deleteReplication(key);
        return dht.deleteKey(key);
    }

    /**
     * Synchronizes remote replicas from origin, by sending new keys and informing about old ones.
     * If the origin is no longer considered to be a valid replica holder, then just delete all of
     * its keys.
     *
     * @param origin Remote node with some replicas.
     * @param keys
     */
    @SuppressWarnings("unchecked")
    public void synchronizeReplicas(NodeInfo origin, HashSet<BigInteger> keys) {
        HashSet<BigInteger> keysToDelete;

        if (fingerTable.getSuccessors().contains(origin))
            keysToDelete = keys;
        else {
            keysToDelete = (HashSet<BigInteger>) keys.clone();
            keysToDelete.removeAll(dht.getAllKeys());
        }

        try {
            Mailman.sendOperation(origin, new ReplicationSyncResultOperation(origin, keysToDelete));
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        ConcurrentHashMap<BigInteger, byte[]> toReplicate = dht.getDifference(keys);
        replicateTo(toReplicate, origin);
    }

    public void deleteReplicas(NodeInfo origin, HashSet<BigInteger> keysToDelete) {
        ConcurrentHashMap<BigInteger, byte[]> originReplicas = replicatedValues.get(origin.getId());
        keysToDelete.forEach(originReplicas::remove);

        if (originReplicas.size() == 0)
            replicatedValues.remove(origin.getId());
    }
}
