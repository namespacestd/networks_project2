
Alexander Dong (aqd2000)
Computer Networks Programming Assignment #2
Due: 5/8/2014

a. Brief Description of Code
    Bfclient.java is a java program that simulates the interactions between different nodes employing the Bellman-Ford algorithm. Every x seconds (as specified in the config-file given to it at runtime), routing updates are sent to all of its neighboring nodes (which are also specified in the config-file). It is able to handle a dynamic environment where nodes are constantly entering and leaving, and using costs associated with each individual link, it is able to estimate the best route from one node to another. In addition to all of this, it also has the built-in functionality of being able to send different file chunks to a target client. When the client detects that it has recieved all of the chunks of a file, it is able to merge the chunks into the desired file and save it to disk.

b. Details on Development Environment
    Coding done in Sublime Text 2
    
    java version "1.6.0_31"
    OpenJDK Runtime Environment (IcedTea6 1.13.3) (6b31-1.13.3-1ubuntu1~0.12.04.2)
    OpenJDK 64-Bit Server VM (build 23.25-b01, mixed mode)

c. How to Run the Code
    javac Bfclient.java
    java Bfclient config-file

    OR

    make
    java Bfclient config-file

d. Sample Commands to Invoke Code
    
    showrt - Displays the current routing table
    linkdown <ip_address> <port> - Brings down the neighboring link to a target ip address/port
    linkup <ip_address> <port> <weight> - Brings up the neighboring link to a target ip address/port and sets it to the given weight
    transfer <ip_address> <port> - Transfers the file chunk specified in the config-file to the target ip address/port (only 2 chunks)
    transfer+ <ip_address> <port> <numChunks> - Transfers the file chunk specified in the config-file to the target ip 
                                                address/port (allows for 2+ chunks)
    close - Closes the client

    Client 1: 
        > showrt
        <23:23:40> Distance Vector List is: 
        Destination = 128.59.15.30:1331, Cost = 1.0, Link = (128.59.15.30:1331)
        Destination = 128.59.15.38:1332, Cost = 5.0, Link = (128.59.15.30:1331)

        > linkdown 128.59.15.30 1331

        > showrt
        <23:23:52> Distance Vector List is: 
        Destination = 128.59.15.30:1331, Cost = 54.0, Link = (128.59.15.38:1332)
        Destination = 128.59.15.38:1332, Cost = 50.0, Link = (128.59.15.38:1332)

        > linkup 128.59.15.30 1331 2

        > showrt
        <23:24:10> Distance Vector List is: 
        Destination = 128.59.15.30:1331, Cost = 2.0, Link = (128.59.15.30:1331)
        Destination = 128.59.15.38:1332, Cost = 6.0, Link = (128.59.15.30:1331)

        > transfer 128.59.15.38 1332 

        > transfer+ 128.59.15.38 1332 40
    Client 2:
        File Chunk Received. Path Traversed: 
        128.59.15.31:1330
        128.59.15.30:1331
        128.59.15.38:1332

        Timestamp: 23:24:22
        Chunk Size: 50000
        Still waiting to complete file transfer of chunk size 2, but recieved new chunk transfer of size 40.
        Discarding packet until first transfer done.

e. Additional Functionality
    transfer+ <ip_address> <port> <numChunks> - Transfers the file chunk specified in the config-file to the target ip 
                                                address/port (allows for 2+ chunks)

    Client 1: chunk1, sequence number = 1
    Client 2: chunk2, sequence number = 2
    Client 3: chunk3, sequence number = 3
    Client 4: N/A

    Command: transfer+ <client4 ip_address> <client4 port> 3
    Run above command on Client 1, 2, and 3.
