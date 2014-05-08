
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Arrays;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.FileOutputStream;
import java.nio.file.NoSuchFileException;
import java.net.SocketException;

import java.io.Console;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;

import java.util.Timer;
import java.util.TimerTask;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;

public class Bfclient extends Thread{
    private static final String PATTERN = 
        "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";
    private static double INFINITY = Double.POSITIVE_INFINITY;
    private static int UDP_BUFFER_SIZE = 70000;

    private static byte UPDATE = 10;
    private static byte LINKDOWN = 11;
    private static byte TRANSFER = 12;

    private int localPort;
    private int timeout; //timeout in seconds
    private String fileChunk;
    private int sequenceNumber;
    private ArrayList<RoutingEntry> routingTable;
    private Hashtable<String, ArrayList<RoutingEntry>> neighborTables = new Hashtable<String, ArrayList<RoutingEntry>>();

    private ArrayList<FileChunk> chunksReceived = new ArrayList<FileChunk>();

    private DatagramSocket socket = null;
    private Timer timer = null;

    public Bfclient(Bfclient client) {
        this.localPort = client.getLocalPort();
        this.timeout = client.getTimeout();
        this.fileChunk = client.getFileChunk();
        this.sequenceNumber = client.getSequenceNumber();
        this.routingTable = client.getRoutingTable();
        this.timer = new Timer();
    }
    public Bfclient(int localPort, int timeout, String fileChunk, int sequenceNumber) {
        this.localPort = localPort;
        this.timeout = timeout;
        this.fileChunk = fileChunk;
        this.sequenceNumber = sequenceNumber;
        this.routingTable = new ArrayList<RoutingEntry>();
        this.timer = new Timer();
    }
    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }
    public void setFileChunk(String fileChunk) {
        this.fileChunk = fileChunk;
    }
    public void addToTable(RoutingEntry entry) {
        this.routingTable.add(entry);
    }
    public int getSequenceNumber() {
        return sequenceNumber;
    }
    public int getTimeout() {
        return timeout;
    }
    public int getLocalPort() {
        return localPort;
    }
    public String getFileChunk() {
        return fileChunk;
    }
    public Timer getTimer() {
        return timer;
    }
    public void setTimer(Timer timer) {
        this.timer = timer;
    }
    public String getAddress() {
        InetAddress ownAddress = socket.getInetAddress();
        if(ownAddress == null)
            ownAddress = socket.getLocalAddress();
        return ownAddress.toString();
    }
    public ArrayList<RoutingEntry> getRoutingTable() {
        return routingTable;
    }
    public Hashtable<String, ArrayList<RoutingEntry>> getNeighborTables() {
        return neighborTables;
    }
    public DatagramSocket getSocket() {
        return socket;
    }
    public void closeSocket() {
        socket.close();
    }

    public void sendPacket(byte[] packet, String targetAddress, int targetPort) {
        try {
            InetAddress receiverAddress = InetAddress.getByName(targetAddress);
            
            DatagramPacket outgoingPacket = new DatagramPacket(packet, packet.length, receiverAddress, targetPort);
            socket.send(outgoingPacket);
        }
        catch(IOException ex) {
            ex.printStackTrace();
            System.err.println("IO Exception when sending UDP packet.");
        }
    }

    public void run() {
        try {
            socket = new DatagramSocket(this.localPort);

            while(!this.isInterrupted()) {                
                //System.out.println(socket.getLocalAddress());
                byte[] buffer = new byte[UDP_BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());

                byte type = data[0];
                if(type == UPDATE) {
                    //System.out.println("Update");
                    byte[] packetData = packetData(data);
                    
                    String[] routingEntries = (new String(packetData, "UTF-8")).split("\n");
                    ArrayList<RoutingEntry> neighborRouting = new ArrayList<RoutingEntry>();

                    String[] sourceInformation = routingEntries[0].split(" ");
                    String sourceIP = removeSubnet(sourceInformation[0]);
                    int sourcePort = Integer.parseInt(sourceInformation[1]);

                    for(String entry : routingEntries) {
                        String[] values = entry.split(" ");
                        if(values.length == 6) {
                            String ipAddress = values[0];
                            int port = Integer.parseInt(values[1]);
                            double weight = Double.parseDouble(values[2]);
                            String nextHopAddress = values[3];
                            int nextHopPort = Integer.parseInt(values[4]);

                            double neighborWeight = Double.parseDouble(values[5]);

                            RoutingEntry routingEntry = new RoutingEntry(ipAddress, port, weight, nextHopAddress, nextHopPort);
                            routingEntry.setNeighborWeight(neighborWeight);
                            neighborRouting.add(routingEntry);
                            //System.out.println(routingEntry);
                        }                  
                    }
                    neighborTables.put(sourceIP + ":" + sourcePort, neighborRouting);
                    RoutingEntry entry = findByIPPort(getNeighbors(this), sourceIP, sourcePort);
                    if(entry != null) {
                        entry.startTimer(3 * this.getTimeout(), this);
                    }

                    // CHANGE IT SO THAT WHEN A LINK GOES DOWN, IT IS NO LONGER A NEIGHBOR
                    if(entry != null && entry.getLinkStatus()) {
                        RoutingEntry selfEntry = findByIPPort(neighborRouting, 
                                                                  removeSubnet(getAddress()),
                                                                  localPort);
                        entry.setLinkStatus(false);
                        entry.setNeighborWeight(selfEntry.getWeight());
                        entry.setNeighbor(true);
                    }

                    if(entry != null) { //Node is neighbor
                        if(updateRoutingTable(sourceIP, sourcePort, entry.getWeight(),
                                           this.getRoutingTable(), neighborRouting))
                            sendUpdate(this); // If routing table changed, send update.
                    }
                    else { // Node was not found in existing neighbors -> Add to neighbors
                        RoutingEntry inTable = findByIPPort(this.routingTable, sourceIP, sourcePort);

                        if(inTable == null) { // Node does not already exist in routing table. Create new neighbor entry
                            RoutingEntry selfEntry = findByIPPort(neighborRouting, 
                                                                  removeSubnet(getAddress()),
                                                                  localPort);
                            
                            try {
                                RoutingEntry newNeighborEntry = new RoutingEntry(sourceIP, sourcePort, selfEntry.getWeight());
                                this.routingTable.add(newNeighborEntry);
                                inTable = newNeighborEntry;
                            }
                            catch(Exception ex) {
                                System.err.println("Unknown message format recieved.");
                            }
                        }
                        else { // Set node to neighbor
                            RoutingEntry selfEntry = findByIPPort(neighborRouting, 
                                                                  removeSubnet(getAddress()),
                                                                  localPort);
                            inTable.setNeighbor(true);
                            inTable.setNeighborWeight(selfEntry.getNeighborWeight());
                            inTable.setLinkStatus(false);
                        }

                        // After adding new neighbor, handle other routings.
                        updateRoutingTable(sourceIP, sourcePort, inTable.getWeight(),
                                           this.getRoutingTable(), neighborRouting);
                        sendUpdate(this);
                        inTable.startTimer(3 * this.getTimeout(), this);
                    }

                }
                else if(type == LINKDOWN) {
                    //System.out.println("LINKDOWN");
                    byte[] packetData = packetData(data);                    
                    String[] routingEntries = (new String(packetData, "UTF-8")).split("\n");
                    String[] sourceInformation = routingEntries[0].split(" ");
                    String sourceIP = removeSubnet(sourceInformation[0]);
                    int sourcePort = Integer.parseInt(sourceInformation[1]);

                    try {
                        if(!linkdown(this, sourceIP, sourcePort))
                            System.err.println("Recieved linkdown message, but client not found in neighbor routing table.");
                        else {
                            linkdownConnections(this, sourceIP, sourcePort);
                            sendUpdate(this);
                        }
                    }
                    catch(LinkNotDownException ex) { }
                }
                else if(type == TRANSFER) {                      
                    //System.out.println("TRANSFER");
                    byte[] transferredBytes = packetData(data);

                    int receivedSequenceNumber = convertByteToInt(Arrays.copyOfRange(transferredBytes, 0, 4));
                    int receivedDS = convertByteToInt(Arrays.copyOfRange(transferredBytes, 4, 8));
                    String destinationAddress = new String(Arrays.copyOfRange(transferredBytes, 8, 8 + receivedDS), "UTF-8");
                    int destinationPort = convertByteToInt(Arrays.copyOfRange(transferredBytes, 8 + receivedDS, 12 + receivedDS));
                    
                    int pathSize = convertByteToInt(Arrays.copyOfRange(transferredBytes, 12 + receivedDS, 16 + receivedDS));
                    String chunkPath = new String(Arrays.copyOfRange(transferredBytes, 16 + receivedDS, 16 + receivedDS + pathSize));

                    int dataChunkSize = convertByteToInt(Arrays.copyOfRange(transferredBytes, 16 + receivedDS + pathSize, 20 + receivedDS + pathSize));
                    //System.out.println(dataChunkSize);

                    byte[] receivedFileChunk = Arrays.copyOfRange(transferredBytes, 20 + receivedDS + pathSize, 20 + receivedDS + pathSize + dataChunkSize);

                    /* System.out.println(receivedSequenceNumber);
                    System.out.println(receivedDS);
                    System.out.println(destinationAddress);
                    System.out.println(destinationPort);

                    System.out.println(pathSize);
                    System.out.println(chunkPath); */

                    InetAddress ownAddress = socket.getInetAddress();
                    if(ownAddress == null)
                        ownAddress = socket.getLocalAddress();

                    if(destinationAddress.equals(removeSubnet(ownAddress.toString())) && destinationPort == this.localPort) {
                        //System.out.println("Recipient of file chunk. Adding to recieved chunks.");
                        FileChunk fChunk = new FileChunk(receivedSequenceNumber, receivedFileChunk, chunkPath);

                        System.out.println("File Chunk Received. Path Traversed: ");
                        printPathTraversed(chunkPath);
                        System.out.println();
                        System.out.println("Timestamp: " + fChunk.getTimeReceived());
                        System.out.println("Chunk Size: " + fChunk.getBytes().length);
                        chunksReceived.add(fChunk);

                        if(chunksReceived.size() == 2) {
                            System.out.println("All Chunks Recieved");
                            byte[] firstChunk = fileChunkWithSequenceNumber(chunksReceived, 1).getBytes();
                            byte[] secondChunk = fileChunkWithSequenceNumber(chunksReceived, 2).getBytes();

                            FileOutputStream stream = new FileOutputStream("output");
                            try {
                                stream.write(firstChunk);
                                stream.write(secondChunk);
                            } 
                            catch(Exception ex) {
                                System.err.println("Problem writing merged file to disk.");
                            }
                            finally {
                                stream.close();
                            }
                        }
                    }
                    else {
                        System.out.println("Recieved file chunk to be transferred to be different node. Forwarding file chunk.");
                        sendFileChunk(receivedFileChunk, receivedSequenceNumber, chunkPath, destinationAddress, destinationPort);
                    }                    
                }
            }
        }
        catch(SocketException ex) {
            System.err.println("Socket closed.");
        }
        catch(IOException ex) {
            System.err.println("IOException from Socket.");
        }
    }

    public void printPathTraversed(String chunkPath) {
        String[] nodes = chunkPath.split(",");
        for(String node : nodes) {
            System.out.println(node.trim());
        }
        InetAddress ownAddress = socket.getInetAddress();
        if(ownAddress == null)
            ownAddress = socket.getLocalAddress();
        System.out.println(removeSubnet(ownAddress.toString()) + ":" + this.localPort);
    }

    public boolean updateRoutingTable(String sourceIP, int sourcePort, double sourceWeight,
                                   ArrayList<RoutingEntry> currentTable, 
                                   ArrayList<RoutingEntry> receivedTable) {

        if(currentTable == null || receivedTable == null)
            return false;

        boolean changed = false;
        InetAddress ownAddress = socket.getInetAddress();
        if(ownAddress == null)
            ownAddress = socket.getLocalAddress();

        for(RoutingEntry receivedEntry : receivedTable) {
            String ipAddress = receivedEntry.getIPAddress();
            int port = receivedEntry.getListeningPort();

            if(ipAddress.equals(removeSubnet(ownAddress.toString())) && port == this.localPort) {
                RoutingEntry existingEntry = findByIPPort(currentTable, sourceIP, sourcePort);

                double newWeight = receivedEntry.getWeight();
                double currentWeight = existingEntry.getWeight();

                if(newWeight < currentWeight) {
                    existingEntry.setNextHopAddress(sourceIP);
                    existingEntry.setNextHopPort(sourcePort);
                    existingEntry.setWeight(newWeight);
                    changed = true;
                }
            }
            else {
                RoutingEntry existingEntry = findByIPPort(currentTable, ipAddress, port);
                if(existingEntry == null) { //If entry does not exist in routing table
                    receivedEntry.setNextHopAddress(sourceIP);
                    receivedEntry.setNextHopPort(sourcePort);
                    receivedEntry.setWeight(sourceWeight + receivedEntry.getWeight());
                    this.routingTable.add(receivedEntry);
                    changed = true;
                }
                else { // Else compare the path cost and change routing if 'shorter'
                    double newWeight = sourceWeight + receivedEntry.getWeight();
                    double currentWeight = existingEntry.getWeight();

                    if(newWeight < currentWeight) {
                        existingEntry.setNextHopAddress(sourceIP);
                        existingEntry.setNextHopPort(sourcePort);
                        existingEntry.setWeight(newWeight);
                        //System.out.println(existingEntry);
                        changed = true;
                    }
                    else if((ipAddress.equals(existingEntry.getIPAddress()) 
                                && port == existingEntry.getListeningPort()) &&
                            (existingEntry.getNextHopAddress().equals(sourceIP)
                                && existingEntry.getNextHopPort() == sourcePort) &&
                            (receivedEntry.getWeight() == INFINITY)) {
                        existingEntry.setWeight(INFINITY);
                        changed = true;
                    }
                }
                
            }
        }
        return changed;
    }

    public String removeSubnet(String ipAddress) {
        return ipAddress.split("/")[0];
    }
    public static RoutingEntry findByIPPort(ArrayList<RoutingEntry> routingTable, String ipAddress, int port) {
        for(RoutingEntry entry : routingTable) {
            if(entry.getIPAddress().equals(ipAddress) && entry.getListeningPort() == port)
                return entry;
        }
        return null;
    }

    public ArrayList<RoutingEntry> findByNextHopIPPort(ArrayList<RoutingEntry> routingTable, String ipAddress, int port) {
        ArrayList<RoutingEntry> entries = new ArrayList<RoutingEntry>();
        for(RoutingEntry entry : routingTable) {
            if(entry.getNextHopAddress().equals(ipAddress) && entry.getNextHopPort() == port)
                entries.add(entry);
        }
        return entries;
    }

    public static void startTimer(final Bfclient client) {
        try {
            client.getTimer().cancel();
        }
        catch(Exception ex) {}

        client.setTimer(new Timer());
        client.getTimer().schedule(new TimerTask() {
            @Override
            public void run() {
                sendUpdate(client);
            }
        }, client.getTimeout() * 1000);
    }

    public static void main(String[] args) {
        Bfclient initClient = null;

        if(args.length != 1) {
            System.err.println("Usage: java Bfclient [config-file]");
            return;
        }
        
        String targetFile = args[0];
    
        try (BufferedReader br = new BufferedReader(new FileReader(targetFile))) {
            String sCurrentLine;
 
            try {
                String localConfig = br.readLine();
                String[] values = localConfig.split(" ");

                int localPort = Integer.parseInt(values[0]);
                int timeout = Integer.parseInt(values[1]);
                String fileChunk = values[2];
                int sequenceNumber = Integer.parseInt(values[3]);

                initClient = new Bfclient(localPort, timeout, fileChunk, sequenceNumber);
            }
            catch(Exception ex) {
                System.err.println("Error parsing configuration for local client.");
                System.err.println("[localport timeout file_chunk_to_transfer file_sequence_number]");
                return;
            }

            while ((sCurrentLine = br.readLine()) != null) {
                try {
                    String[] values = sCurrentLine.split(" ");
                    String[] ipAddress_port = values[0].split(":");

                    String ipAddress = ipAddress_port[0];
                    int port = Integer.parseInt(ipAddress_port[1]);
                    double weight = Double.parseDouble(values[1]);

                    RoutingEntry entry = new RoutingEntry(ipAddress, port, weight);
                    initClient.addToTable(entry);
                }
                catch(Exception ex) {
                    System.err.println("Error parsing neighbor link information.");
                    System.err.println("[ip_address:port weight]");
                    return;
                }
            }
        } 
        catch (IOException e) {
            e.printStackTrace();
        }

        final Bfclient client = new Bfclient(initClient);

        /* UDP Connection */
        client.start();

        /* Periodic update */
        startTimer(client);

        /* Neighbor timers */
        for(RoutingEntry entry : getNeighbors(client)) {
            entry.startTimer(3 * client.getTimeout(), client);
        }
        /*timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendUpdate(client);
            }
        }, client.getTimeout()*1000, client.getTimeout()*1000);*/

        /* Command Line Interface */
        Console console = System.console();
        String input = "";

        while(!input.equalsIgnoreCase("close")) {
            input = console.readLine("> ");

            if(input.equalsIgnoreCase("showrt")) {
                showRT(client);
            }
            else if(input.equalsIgnoreCase("test")) {
                sendUpdate(client);
            }
            else if(input.equalsIgnoreCase("test2")) {
                for(RoutingEntry entry : getNeighbors(client)) {
                    System.out.println(entry);
                    System.out.println(entry.getNeighborWeight());
                }
            }
            else if(input.equalsIgnoreCase("test3")) {
                String file = client.getFileChunk();
                int sequenceNumber = client.getSequenceNumber();

                try {
                    Path path = Paths.get(file);
                    byte[] data = Files.readAllBytes(path);
                    client.sendPacket(encapsulateFileChunk(sequenceNumber, data, "0.0.0.0", 1331, "0.0.0.0:1, 0.0.0.0:2, 0.0.0.0:3"), "0.0.0.0", 1331);
                }
                catch(NoSuchFileException ex) {
                    System.err.println("File does not exist.");
                }
                catch(Exception ex) {
                    ex.printStackTrace();
                    System.err.println("Problem reading target file: " + file);
                }
            }
            else {
                String[] values = input.split(" ");
                if(values[0].equalsIgnoreCase("linkdown")) {
                    if(values.length == 3) {
                        try {
                            String ipAddress = values[1];
                            int port = Integer.parseInt(values[2]);

                            if(!linkdown(client, ipAddress, port))
                                System.err.println("Target IP Address / Port not found.");
                            else {
                                System.out.println("linkdown set");
                                client.sendPacket(encapsulateData(LINKDOWN, 
                                                  sourceAddressPortString(client)), 
                                                  ipAddress, port);
                                linkdownConnections(client, ipAddress, port);
                                sendUpdate(client);
                            }
                        }
                        catch(LinkNotDownException ex) {
                            System.err.println("Link already down.");
                        }
                        catch(Exception ex) {
                            System.err.println("Usage: linkdown ip_address port");
                        }
                    }
                    else
                        System.err.println("Usage: linkdown ip_address port");
                }
                if(values[0].equalsIgnoreCase("transfer")) {
                    if(values.length == 3) {
                        try {
                            String ipAddress = values[1];
                            int port = Integer.parseInt(values[2]);

                            sendFileChunk(client, ipAddress, port);
                        }
                        catch(Exception ex) {
                            System.err.println("Usage: transfer ip_address port");
                        }
                    }
                    else
                        System.err.println("Usage: linkdown ip_address port");
                }
                if(values[0].equalsIgnoreCase("linkup")) {
                    if(values.length == 3) {
                        try {
                            String[] ipAddress_port = values[1].split(":");
                            String ipAddress = ipAddress_port[0];
                            int port = Integer.parseInt(ipAddress_port[1]);
                            double weight = Double.parseDouble(values[2]);

                            if(!linkup(client, ipAddress, port, weight))
                                System.err.println("Target IP Address / Port not found.");
                            sendUpdate(client);
                        }
                        catch(LinkNotDownException ex) {
                            System.err.println("Cannot call linkup on a node that's already up.");
                        }
                        catch(Exception ex) {
                            System.err.println("Usage: linkup ip_address:port weight");
                        }
                    }
                    else
                        System.err.println("Usage: linkup ip_address:port weight");
                }
            }

            System.out.println();
        }
        client.closeSocket();

        try {
            client.getTimer().cancel();

            for(RoutingEntry entry : getNeighbors(client))
                entry.getTimer().cancel();
        }
        catch(Exception ex) {}

        client.interrupt();
        System.exit(1);
    }

    public static void sendUpdate(Bfclient client) {
        startTimer(client);

        ArrayList<RoutingEntry> neighbors = getNeighbors(client);
        for(RoutingEntry neighbor : neighbors) {
            if(!neighbor.getLinkStatus()) {
                client.sendPacket(encapsulateData(UPDATE, 
                  sourceAddressPortString(client) + 
                  poisonReverseTableString(client, neighbor)), 
                neighbor.getIPAddress(), neighbor.getListeningPort());
            }
        }
    }

    public static void sendFileChunk(Bfclient client, String targetAddress, int targetPort) {
        String file = client.getFileChunk();
        int sequenceNumber = client.getSequenceNumber();

        InetAddress ownAddress = client.getSocket().getInetAddress();
        if(ownAddress == null)
            ownAddress = client.getSocket().getLocalAddress();

        try {
            Path path = Paths.get(file);
            byte[] data = Files.readAllBytes(path);
            RoutingEntry routingEntry = client.findByIPPort(client.getRoutingTable(), targetAddress, targetPort);

            if(routingEntry != null) {
                System.out.println(routingEntry.getNextHopAddress());
                System.out.println(routingEntry.getNextHopPort());
                client.sendPacket(encapsulateFileChunk(sequenceNumber, 
                                                        data, 
                                                        targetAddress, 
                                                        targetPort, client.removeSubnet(ownAddress.toString()) + ":" + client.getLocalPort() + ","), 
                                    routingEntry.getNextHopAddress(), routingEntry.getNextHopPort());
            }
            else
                System.err.println("No path to address <" + targetAddress + ":" + targetPort + "> found in routing table.");
        }
        catch(NoSuchFileException ex) {
            System.err.println("File does not exist.");
        }
        catch(Exception ex) {
            ex.printStackTrace();
            System.err.println("Problem reading target file: " + file);
        }
    }
    public void sendFileChunk(byte[] data, int sequenceNumber, String currentPath, String targetAddress, int targetPort) {
        InetAddress ownAddress = socket.getInetAddress();
        if(ownAddress == null)
            ownAddress = socket.getLocalAddress();

        RoutingEntry routingEntry = findByIPPort(this.getRoutingTable(), targetAddress, targetPort);

        System.out.println(data.length);
        if(routingEntry != null) {
            this.sendPacket(encapsulateFileChunk(sequenceNumber, 
                                                    data, 
                                                    targetAddress, 
                                                    targetPort, 
                                                    currentPath + removeSubnet(ownAddress.toString()) + ":" + this.localPort + ","), 
                                                    routingEntry.getNextHopAddress(), routingEntry.getNextHopPort());
        }
        else
            System.err.println("No path to address <" + targetAddress + ":" + targetPort + "> found in routing table.");
    }

    public static byte[] encapsulateData(byte protocol, String data) {
        byte[] code = new byte[1];
        code[0] = protocol;

        byte[] dataBytes = data.getBytes();

        byte[] packet = new byte[code.length + dataBytes.length];
        System.arraycopy(code, 0, packet, 0, code.length);
        System.arraycopy(dataBytes, 0, packet, code.length, dataBytes.length);
        return packet;
    }

    public static byte[] encapsulateFileChunk(int sequenceNumber, byte[] dataBytes, String targetIp, int targetPort, String currentPath) {
        byte[] code = new byte[1];
        code[0] = TRANSFER;
        
        byte[] seqNumber = intToByteArray(sequenceNumber);
        int sEnd = code.length + seqNumber.length;
        byte[] destination = targetIp.getBytes();        
        byte[] destinationSize = intToByteArray(destination.length);

        int dsEnd = sEnd + destinationSize.length;
        int dEnd = dsEnd + destination.length;
        byte[] destinationPort = intToByteArray(targetPort);
        int dpEnd = dEnd + destinationPort.length;

        byte[] pathBytes = currentPath.getBytes();
        byte[] pathSize = intToByteArray(pathBytes.length);

        int psEnd = dpEnd + pathSize.length;
        int pEnd = psEnd + pathBytes.length;

        byte[] dataSize = intToByteArray(dataBytes.length);
        System.out.println(dataBytes.length);
        System.out.println(dataSize.length);

        byte[] packet = new byte[pEnd + dataBytes.length + dataSize.length];
        System.arraycopy(code, 0, packet, 0, code.length);
        System.arraycopy(seqNumber, 0, packet, code.length, seqNumber.length);
        System.arraycopy(destinationSize, 0, packet, sEnd, destinationSize.length);
        System.arraycopy(destination, 0, packet, dsEnd, destination.length);        
        System.arraycopy(destinationPort, 0, packet, dEnd, destinationPort.length);
        System.arraycopy(pathSize, 0, packet, dpEnd, pathSize.length);
        System.arraycopy(pathBytes, 0, packet, psEnd, pathBytes.length);
        System.arraycopy(dataSize, 0, packet, pEnd, dataSize.length);
        System.arraycopy(dataBytes, 0, packet, pEnd + dataSize.length, dataBytes.length);
        System.out.println(packet.length);
        return packet;
    }

    public static byte[] intToByteArray(int value) {
        return new byte[] {
                (byte)(value >>> 24),
                (byte)(value >>> 16),
                (byte)(value >>> 8),
                (byte)value};
    }
    public static int convertByteToInt(byte[] buf) {           
       int intArr[] = new int[buf.length / 4];
       int offset = 0;
       for(int i = 0; i < intArr.length; i++) {
          intArr[i] = (buf[3 + offset] & 0xFF) | ((buf[2 + offset] & 0xFF) << 8) |
                      ((buf[1 + offset] & 0xFF) << 16) | ((buf[0 + offset] & 0xFF) << 24);  
       offset += 4;
       }
       return intArr[0];    
    }
    public static byte[] packetData(byte[] bytes) {
        byte[] data = new byte[UDP_BUFFER_SIZE];
        System.arraycopy(bytes, 1, data, 0, bytes.length-1);
        return data;
    }

    public static String routingTableString(Bfclient client) {
        String routingTable = "";
        for(RoutingEntry entry : client.getRoutingTable()) {
            String line = entry.getIPAddress() + " " + entry.getListeningPort() + " " + entry.getWeight()
                                + " " + entry.getNextHopAddress() + " " + entry.getNextHopPort() + "\n";
            routingTable += line;
        }
        return routingTable;
    }
    public static String poisonReverseTableString(Bfclient client, RoutingEntry entry) {
        return poisonReverseTableString(client, entry.getIPAddress(), entry.getListeningPort());
    }
    public static String poisonReverseTableString(Bfclient client, String destAddress, int destPort) {
        String routingTable = "";
        for(RoutingEntry entry : client.getRoutingTable()) {
            String targetAddress = entry.getIPAddress();
            int targetPort = entry.getListeningPort();
            String nextHopAddress = entry.getNextHopAddress();
            int nextHopPort = entry.getNextHopPort();
            double linkWeight = entry.getWeight();

            /*If the routing entry is not an entry for the path to the destination address/port 
                and the destination address/port is not itself part of the path for the entry. */
            if(!(targetAddress.equals(destAddress) && targetPort == destPort) &&
                nextHopAddress.equals(destAddress) && nextHopPort == destPort)
                linkWeight = INFINITY;

            String line = targetAddress + " " + targetPort + " " + linkWeight
                                + " " + nextHopAddress + " " + nextHopPort
                                + " " + entry.getNeighborWeight() + "\n";
            routingTable += line;
        }
        return routingTable;
    }
    public static String sourceAddressPortString(Bfclient client) {
        InetAddress ownAddress = client.getSocket().getInetAddress();
        if(ownAddress == null)
            ownAddress = client.getSocket().getLocalAddress();

        return ownAddress.toString() + " " + client.getLocalPort() + "\n";
    }
    public static void showRT(Bfclient client) {
        Calendar cal = Calendar.getInstance();
        cal.getTime();
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        
        String currentTime = sdf.format(cal.getTime());

        System.out.println("<" + currentTime + "> Distance Vector List is: ");

        for(RoutingEntry entry : client.getRoutingTable()) {
            System.out.println("Destination = " + entry.getIPAddress() + ":" + entry.getListeningPort() + ", "
                                + "Cost = " + entry.getWeight() 
                                + ", Link = (" + entry.getNextHopAddress() + ":" + entry.getNextHopPort() + ")");
        }
    }

    public static boolean linkdown(Bfclient client, String ipAddress, int port) throws LinkNotDownException {
        for(RoutingEntry entry : getNeighbors(client)) {
            if(entry.getIPAddress().equalsIgnoreCase(ipAddress) && entry.getListeningPort() == port) {
                if(entry.getLinkStatus())
                    throw new LinkNotDownException("Link already down.");

                if(entry.getNextHopAddress().equals(ipAddress) && entry.getNextHopPort() == port) {
                    entry.setWeight(INFINITY);
                    recalculateNodeFromNeighborWeights(client, ipAddress, port, entry);
                }

                entry.setNeighbor(false);
                entry.setLinkStatus(true);
                //
                entry.setNeighborWeight(INFINITY);
                return true;
            }
        }
        return false;
    }

    public static void recalculateNodeFromNeighborWeights(Bfclient client, String ipAddress, int port, RoutingEntry entry) {
        String minAddress = null;
        int minPort = -1;
        RoutingEntry minEntry = null;
        double nodeWeight = -1; 

        Hashtable<String, ArrayList<RoutingEntry>> neighborTables = client.getNeighborTables();

        for(String key : neighborTables.keySet()) {
            ArrayList<RoutingEntry> entries = neighborTables.get(key);
            RoutingEntry relevantEntry = findByIPPort(entries, ipAddress, port);
            
            String[] addressPort = key.split(":");
            /*System.out.println(key);
            for(RoutingEntry e : entries)
                System.out.println(e);*/

            if(!(addressPort[0].equals(ipAddress) && Integer.parseInt(addressPort[1]) == port) && relevantEntry != null) {
                String nextHopAddress = relevantEntry.getNextHopAddress();
                int nextHopPort = relevantEntry.getNextHopPort();
                RoutingEntry nextHopEntry = findByIPPort(getNeighbors(client), addressPort[0], Integer.parseInt(addressPort[1]));
                double currentNodeWeight = -1;

                if(nextHopEntry.getNextHopAddress().equals(ipAddress) && nextHopEntry.getNextHopPort() == port)
                    currentNodeWeight = nextHopEntry.getNeighborWeight();
                else
                    currentNodeWeight = nextHopEntry.getWeight();

                if(!(nextHopAddress.equals(client.getAddress()) && nextHopPort == client.getLocalPort())
                    && (minAddress == null || (minAddress != null && (minEntry.getWeight() + nodeWeight > relevantEntry.getWeight() + currentNodeWeight)))) {
                    minAddress = addressPort[0].trim();
                    minPort = Integer.parseInt(addressPort[1].trim());
                    minEntry = relevantEntry;
                    nodeWeight = currentNodeWeight;
                }
            }
        }
        if(minAddress != null) {
            entry.setWeight(minEntry.getWeight() + nodeWeight);
            entry.setNextHopAddress(minAddress);
            entry.setNextHopPort(minPort);
            System.out.println(entry);
        }
    }

    public static void linkdownConnections(Bfclient client, String ipAddress, int port) {
        for(RoutingEntry entry : client.getRoutingTable()) {
            //If the linkdown is part of another connection.
            if(!(entry.getIPAddress().equalsIgnoreCase(ipAddress) && entry.getListeningPort() == port) &&
                entry.getNextHopAddress().equalsIgnoreCase(ipAddress) && entry.getNextHopPort() == port) {
                System.out.println("TEST");
                if(entry.isNeighbor() && !entry.getLinkStatus()) {
                    recalculateNodeFromNeighborWeights(client, ipAddress, port, entry);

                    if(entry.getWeight() > entry.getNeighborWeight()) {
                        entry.setWeight(entry.getNeighborWeight());
                        entry.setNextHopAddress(entry.getIPAddress());
                        entry.setNextHopPort(entry.getListeningPort());
                    }
                }
                else {
                    entry.setWeight(INFINITY);
                    recalculateNodeFromNeighborWeights(client, ipAddress, port, entry);
                }
            }
        }
        /*
        for(RoutingEntry entry : getNeighbors(client)) {
            if(entry.getIPAddress().equals(entry.getNextHopAddress()) 
                && entry.getListeningPort() == entry.getNextHopPort()
                && !entry.getLinkStatus()) {
                System.out.println(entry.getNeighborWeight());
                entry.setWeight(entry.getNeighborWeight());
            }
        }*/
    }

    public static boolean linkup(Bfclient client, String ipAddress, int port, double weight) throws LinkNotDownException {
        for(RoutingEntry entry : client.getRoutingTable()) {
            if(entry.getIPAddress().equalsIgnoreCase(ipAddress) && entry.getListeningPort() == port) {
                if(!entry.getLinkStatus())
                    throw new LinkNotDownException("Link is not Down");
                entry.setNeighbor(true);
                entry.setWeight(weight);
                entry.setNeighborWeight(weight);
                entry.setNextHopAddress(ipAddress);
                entry.setNextHopPort(port);
                entry.setLinkStatus(false);
                return true;
            }
        }
        return false;
    }

    public static boolean validate(final String ip) {
        Pattern pattern = Pattern.compile(PATTERN);
        Matcher matcher = pattern.matcher(ip);
        return matcher.matches();             
    }

    public static ArrayList<RoutingEntry> getNeighbors(Bfclient client) {
        ArrayList<RoutingEntry> neighbors = new ArrayList<RoutingEntry>();
        for(RoutingEntry entry : client.getRoutingTable()) {
            if(entry.isNeighbor())
                neighbors.add(entry);
        }
        return neighbors;
    }

    public static FileChunk fileChunkWithSequenceNumber(ArrayList<FileChunk> fileChunks, int sequenceNumber) {
        for(FileChunk chunk : fileChunks)
            if(chunk.getSequenceNumber() == sequenceNumber)
                return chunk;
        return null;
    }

    public static class FileChunk {
        private int sequenceNumber;
        private byte[] bytes;
        private String pathTraversed;
        private String timeReceived;

        public FileChunk(int sequenceNumber, byte[] bytes, String pathTraversed) {
            this.sequenceNumber = sequenceNumber;
            this.bytes = bytes;
            this.pathTraversed = pathTraversed;
            
            Calendar cal = Calendar.getInstance();
            cal.getTime();
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        
            this.timeReceived = sdf.format(cal.getTime());
        }
        public int getSequenceNumber() {
            return sequenceNumber;
        }
        public byte[] getBytes() {
            return bytes;
        }
        public String getTimeReceived() {
            return timeReceived;
        }
        public String getPathTraversed() {
            return pathTraversed;
        }
    }

    private static class RoutingEntry {
        private String ipAddress;
        private int listeningPort;
        private double weight;

        private String nextHopAddress;
        private int nextHopPort;

        private boolean isNeighbor;
        private double neighborWeight;

        private boolean linkdown;

        private Timer timer;

        public RoutingEntry(String ipAddress, int listeningPort, double weight) throws Exception {
            if(!Bfclient.validate(ipAddress))
                throw new Exception("Invalid IP Address given."); 

            this.ipAddress = ipAddress;
            this.listeningPort = listeningPort;
            this.weight = weight;

            this.nextHopAddress = ipAddress;
            this.nextHopPort = listeningPort;

            this.isNeighbor = true;
            this.neighborWeight = weight;

            this.linkdown = false;


            this.timer = new Timer();
        }

        public RoutingEntry(String ipAddress, int listeningPort, double weight, String nextHopAddress, int nextHopPort) {
            this.ipAddress = ipAddress;
            this.listeningPort = listeningPort;
            this.weight = weight;

            this.nextHopAddress = nextHopAddress;
            this.nextHopPort = nextHopPort;
            this.isNeighbor = false;

            this.linkdown = false;

            this.timer = new Timer();
        }
        public void startTimer(int timeout, final Bfclient client) {
            try {
                timer.cancel();
            } 
            catch(Exception ex) {}
            
            timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        Bfclient.linkdown(client, ipAddress, listeningPort);
                        Bfclient.linkdownConnections(client, ipAddress, listeningPort);
                    }
                    catch(LinkNotDownException ex) {}
                }
            }, timeout * 1000);
            
        }
        public Timer getTimer() {
            return timer;
        }
        public boolean getLinkStatus() {
            return linkdown;
        }
        public boolean isNeighbor() {
            return this.isNeighbor;
        }
        public String getIPAddress() {
            return this.ipAddress;
        }
        public int getListeningPort() {
            return listeningPort;
        }
        public double getWeight() {
            return weight;
        }
        public String getNextHopAddress() {
            return nextHopAddress;
        }
        public int getNextHopPort() {
            return nextHopPort;
        }
        public double getNeighborWeight() {
            return neighborWeight;
        }
        public void setTimer(Timer timer) {
            this.timer = timer;
        }
        public void setLinkStatus(boolean linkdown) {
            this.linkdown = linkdown;
        }
        public void setNeighbor(boolean isNeighbor) {
            this.isNeighbor = isNeighbor;
        }
        public void setIPAddress(String ipAddress) {
            this.ipAddress = ipAddress;
        }
        public void setListeningPort(int listeningPort) {
            this.listeningPort = listeningPort;
        }
        public void setWeight(double weight) {
            this.weight = weight;
        }
        public void setNextHopAddress(String ipAddress) {
            this.nextHopAddress = ipAddress;
        }
        public void setNextHopPort(int port) {
            this.nextHopPort = port;
        }
        public void setNeighborWeight(double weight) {
            this.neighborWeight = weight;
        }
        public String toString() {
            return "Destination = " + this.getIPAddress() + ":" + this.getListeningPort() + ", "
                    + "Cost = " + this.getWeight() 
                    + ", Link = (" + this.getNextHopAddress() + ":" + this.getNextHopPort() + ")";
        }
    }

    private static class LinkNotDownException extends Exception {
        public LinkNotDownException(String message) {
            super(message);
        }
    }
}