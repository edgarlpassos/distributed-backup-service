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
    public static final int OPERATION_MAX_FAILED_ATTEMPTS = 3;
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

    /**
     * Requests to the given successor their predecessor.
     *
     * @param successor
     * @return
     * @throws IOException
     */
    private CompletableFuture<NodeInfo> requestSuccessorPredecessor(NodeInfo successor) throws IOException {
        /* Check if the request is already being made */

        CompletableFuture<NodeInfo> requestResult = ongoingPredecessorLookup;
        if (requestResult != null)
            return requestResult;

        requestResult = new CompletableFuture<>();
        ongoingPredecessorLookup = requestResult;
        try {
            Mailman.sendOperation(successor, new RequestPredecessorOperation(self));
        } catch (IOException e) {
            ongoingPredecessorLookup = null;
            requestResult.completeExceptionally(e);
            throw e;
        }

        return requestResult;
    }

    /**
     * Gets the Node Info.
     * @return
     */
    public NodeInfo getInfo() {
        return self;
    }

    /**
     * Checks if the given key belongs to their successor.
     *
     * @param key
     * @return
     */
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

        int attempts = OPERATION_MAX_FAILED_ATTEMPTS;

        /* Get the successor's predecessor, which will be the new node's predecessor */
        while (attempts > 0) {
            try {
                requestSuccessorPredecessor(successor).thenAcceptAsync(fingerTable::updatePredecessor, threadPool).get(LOOKUP_TIMEOUT, TimeUnit.MILLISECONDS);
                break;
            } catch (CancellationException | ExecutionException | InterruptedException | TimeoutException | IOException e) {
                attempts--;
            }
        }

        return attempts > 0;
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

    /**
     *Finish the Predecessor Request.
     *
     * @param predecessor
     */
    public void finishPredecessorRequest(NodeInfo predecessor) {
        fingerTable.updatePredecessor(predecessor);

        ongoingPredecessorLookup.complete(predecessor);
        ongoingPredecessorLookup = null;
    }

    /**
     * Initiates the Stabilization Protocol.
     */
    public void initiateStabilization() {
        stabilizationExecutor.scheduleWithFixedDelay(this::stabilizationProtocol, 5, 5, TimeUnit.SECONDS);

    }

    /**
     * Starts the stabilization Protocol.
     */
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
            NodeInfo owner = null;
            int attempts = OPERATION_MAX_FAILED_ATTEMPTS;

            while (attempts > 0) {
                try {
                    owner = fingerTable.lookup(BigInteger.valueOf(entry.getKey())).get(LOOKUP_TIMEOUT, TimeUnit.MILLISECONDS);

                    Mailman.sendOperation(
                            owner,
                            new ReplicationSyncOperation(
                                    self,
                                    new HashSet<>(Collections.list(entry.getValue().keys()))));

                    break;
                } catch (TimeoutException | InterruptedException | ExecutionException ignored) {
                    /* These exceptions are thrown by the lookup, and it already handles node failure on lookup. */
                    attempts--;
                } catch (IOException e) {
                    e.printStackTrace();
                    attempts--;
                }
            }

            if (attempts <= 0) {
                if (owner != null)
                    informAboutFailure(owner);
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

    /**
     * Stores the key and the value.
     *
     * @param key
     * @param value
     * @return
     */
    public boolean storeKey(BigInteger key, byte[] value) {
        boolean result = dht.storeKey(key, value);
        ensureReplication(key, value);
        return result;
    }

    /**
     * It ensures that all the Replication of the given key and the value are done.
     *
     * @param key
     * @param value
     */
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

            int attempts = OPERATION_MAX_FAILED_ATTEMPTS;
            while (attempts > 0) {
                try {
                    Mailman.sendOperation(nthSuccessor, new ReplicationOperation(self, key, value));
                    break;
                } catch (IOException e) {
                    attempts--;
                }
            }

            /* If the operation exceeded its max attempts, then inform about its nthSuccessor failure and retry.
             * After informing, the successor list will be updated and a new node will be selected, if available. */
            if (attempts <= 0) {
                informAboutFailure(nthSuccessor);
                i--;
            }
        }
    }

    /**
     * Stores a replica.
     *
     * @param node
     * @param key
     * @param value
     */
    public void storeReplica(NodeInfo node, BigInteger key, byte[] value) {
        ConcurrentHashMap<BigInteger, byte[]> replicas = replicatedValues.getOrDefault(node.getId(), new ConcurrentHashMap<>());
        replicas.put(key, value);
        replicatedValues.putIfAbsent(node.getId(), replicas);
    }

    /**
     * Gets the value stored locally with given key.
     *
     * @param key
     * @return
     */
    public byte[] getLocalValue(BigInteger key) {
        return dht.getLocalValue(key);
    }

    /**
     * Gets the Distributed Hash Table.
     *
     * @return
     */
    public DistributedHashTable getDistributedHashTable() {
        return dht;
    }

    /**
     *Starts the insert Operation with the given key and value.
     *
     * @param key
     * @param value
     * @return
     */
    CompletableFuture<Boolean> insert(BigInteger key, byte[] value) {
        return operation(ongoingInsertions, new InsertOperation(self, key, value), key);
    }

    /**
     * Sends the given keys to the given destination.
     *
     * @param destination
     * @param keys
     * @return
     * @throws Exception
     */
    private CompletableFuture<Boolean> sendKeysToNode(NodeInfo destination, ConcurrentHashMap<BigInteger, byte[]> keys) throws Exception {
        int destinationId = destination.getId();
        CompletableFuture<Boolean> sending = ongoingKeySendings.putIfAbsent(destinationId);

        if (sending != null)
            return sending;

        Mailman.sendOperation(destination, new SendKeysOperation(self, keys));

        return ongoingKeySendings.get(destinationId);
    }

    /**
     * Updates Data Structures of the given new predecessor.
     *
     * @param newPredecessor
     * @return
     */
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

    /**
     * Informs all the nodes about the existence of the given node.
     *
     * @param node
     */
    public void informAboutExistence(NodeInfo node) {
        fingerTable.informAboutExistence(node);
    }

    /**
     * Informs all the nodes about the failure of the given node.
     *
     * @param node
     */
    public void informAboutFailure(NodeInfo node) {
        if (node.equals(self))
            return;


        System.err.println("Node with ID " + node.getId() + " has failed.");
        NodeInfo predecessor = fingerTable.getPredecessor();

        int removedSuccessorIndex = fingerTable.informSuccessorsOfFailure(node);
        fingerTable.informFingersOfFailure(node);
        fingerTable.informPredecessorOfFailure(node);

        /* If the removed successor index was less than REPLICATION_DEGREE - 1, it means that that successor was
         * replicating this node's local values. As such, we need to replicate to a new successor in order to
         * maintain the REPLICATION_DEGREE. */
        if (removedSuccessorIndex < REPLICATION_DEGREE - 1)
            replicateTo(dht.getLocalValues(), fingerTable.getNthSuccessor(REPLICATION_DEGREE - 1));

        /* If my predecessor fails, then I will take over its keys. */
        if (predecessor.equals(node)) {
            ConcurrentHashMap<BigInteger, byte[]> replicas = replicatedValues.remove(node.getId());
            if (replicas == null)
                return;

            dht.storeKeys(replicas);
            replicateTo(replicas, fingerTable.getNthSuccessor(REPLICATION_DEGREE - 2));
        }
    }

    /**
     * Send all the given replicas to given node.
     *
     * @param replicas
     * @param node
     */
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

    /**
     * Finishes the onLookup of the given target node.
     *
     * @param key
     * @param targetNode
     */
    public void onLookupFinished(BigInteger key, NodeInfo targetNode) {
        informAboutExistence(targetNode);
        fingerTable.ongoingLookups.operationFinished(key, targetNode);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(fingerTable.toString());

        sb.append("\n\nReplicated keys:\n");
        sb.append("NodeID    Key\n");
        replicatedValues.forEach((nodeId, keys) -> keys.forEach((key, value) -> {
            sb.append(nodeId);
            sb.append("          ");
            sb.append(DatatypeConverter.printHexBinary(key.toByteArray()));
            sb.append("\n");
        }));

        return sb.toString();
    }

    /**
     * Stores the given keys of the successor.
     *
     * @param keys
     */
    public void storeSuccessorKeys(ConcurrentHashMap<BigInteger, byte[]> keys) {
        dht.storeKeys(keys);
    }

    /**
     *
     * Starts the Get Operation with the given key.
     * @param key
     * @return
     */
    CompletableFuture<byte[]> get(BigInteger key) {
        return operation(ongoingGets, new GetOperation(self, key), key);
    }

    /**
     * Generalizes all the operations (insert, get and delete).
     *
     * @param operationManager
     * @param operation
     * @param key
     * @param <R>
     * @return
     */
    private <R> CompletableFuture<R> operation(OperationManager<BigInteger, R> operationManager, Operation
            operation, BigInteger key) {
        CompletableFuture<R> operationState = operationManager.putIfAbsent(key);

        if (operationState != null)
            return operationState;

        operationState = operationManager.get(key);

        NodeInfo destination = null;
        int attempts = OPERATION_MAX_FAILED_ATTEMPTS;
        while (attempts > 0) {
            try {
                destination = fingerTable.lookup(key).get(LOOKUP_TIMEOUT, TimeUnit.MILLISECONDS);
                break;
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                attempts--;

                if (attempts <= 0) {
                    fingerTable.ongoingLookups.operationFailed(key, new KeyNotFoundException());
                    operationManager.operationFailed(key, e);
                    return operationState;
                }
            }
        }

        attempts = OPERATION_MAX_FAILED_ATTEMPTS;
        while (attempts > 0) {
            try {
                Mailman.sendOperation(destination, operation);
                break;
            } catch (Exception e) {
                attempts--;

                if (attempts <= 0) {
                    operationManager.operationFailed(key, e);
                    return operationState;
                }

            }
        }

        return operationState;
    }

    /**
     * Starts the Delete Operation woth the given key.
     *
     * @param key
     * @return
     */
    CompletableFuture<Boolean> delete(BigInteger key) {
        return operation(ongoingDeletes, new DeleteOperation(self, key), key);
    }

    /**
     * Removes from all Data structures of the node the value with the given key.
     *
     * @param key
     * @return
     */
    public boolean removeValue(BigInteger key) {
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
            keysToDelete.removeAll(dht.getKeySet());
        }

        int attempts = OPERATION_MAX_FAILED_ATTEMPTS;
        while (attempts > 0) {
            try {
                Mailman.sendOperation(origin, new ReplicationSyncResultOperation(self, keysToDelete));
                break;
            } catch (IOException e) {
                attempts--;
                e.printStackTrace();
            }
        }

        if (attempts <= 0)
            return;

        ConcurrentHashMap<BigInteger, byte[]> toReplicate = dht.getDifference(keys);
        replicateTo(toReplicate, origin);
    }

    /**
     * Updates the Data structures of the replicated Values of the given node,
     * deleting the given keys.
     *
     * @param origin
     * @param keysToDelete
     */
    public void updateReplicas(NodeInfo origin, HashSet<BigInteger> keysToDelete) {
        ConcurrentHashMap<BigInteger, byte[]> originReplicas = replicatedValues.get(origin.getId());

        if (originReplicas != null) {
            keysToDelete.forEach(originReplicas::remove);

            if (originReplicas.size() == 0)
                replicatedValues.remove(origin.getId());

        }
    }
}
