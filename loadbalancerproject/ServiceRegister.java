package net.floodlightcontroller.loadbalancerproject;

import java.util.ArrayList;

import org.projectfloodlight.openflow.types.IPv4Address;

public class ServiceRegister {
	/**
	 * The basic component of the ServiceRegister class. It contains the IP
	 * anycast address with an associated list of physical IP addresses of
	 * the servers which have subscribed to the former anycast address.
	 */
	private class Entry {
		public IPv4Address anyAddr;
		public ArrayList<ServerEntry> servers;
		
		public Entry(IPv4Address anyAddr, ArrayList<ServerEntry> phyAddr) {
			this.anyAddr = anyAddr;
			this.servers = phyAddr;
		}
		
		/**
		 * An entry o is equal to the current entry if its list of physical
		 * addresses is a subset of the list of the current entry.
		 */
		@Override
		public boolean equals(Object o) {
			if ((((Entry)o).anyAddr).equals(this.anyAddr)) {
				if (this.servers != null) {
					if (((Entry)o).servers == null) 
						return false;
					for (ServerEntry server : this.servers) {
						if (!((Entry)o).servers.contains(server)) {
							return false;
						}
					}
				}
				return true;
			}
			return false;
		}
		
		@Override
		public String toString() {
			String addr = "<" + anyAddr.toString() + "> " + servers.toString();
			return addr;
		}
	}
	
	private ArrayList<Entry> reg;
	
	// Constructor
	public ServiceRegister() {
		reg = new ArrayList<>();
	}
	
	private synchronized boolean contains(ServerEntry entry) {
		for (Entry e : reg) {
			if (e.servers.contains(entry)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * For a service, specified by its anycast IP address, a list of service
	 * providers is returned
	 * @param anyAddr The anycast IP address associated with a service
	 * @return The list of the physical IP addresses and the port of the providers of the
	 * service associated with the anycast IP address anyAddr
	 */
	public synchronized ArrayList<ServerEntry> getServers(IPv4Address anyAddr) {
		int i = reg.indexOf(new Entry(anyAddr, null));
		if (i < 0) {
			return null;
		}
		ArrayList<ServerEntry> servers = new ArrayList<ServerEntry>();
		for (ServerEntry s : reg.get(i).servers) {
			servers.add(s);
		}
		return servers;
	}
	
	/**
	 * For a service, specified by its anycast IP address, a list of physical
	 * IP addresses of the service providers is returned
	 * @param anyAddr The anycast IP address associated with a service
	 * @return The list of the physical IP addresses of the providers of the
	 * service associated with the anycast IP address anyAddr
	 */
	public ArrayList<IPv4Address> getServersPhyAddr(IPv4Address anyAddr) {
		ArrayList<ServerEntry> e = getServers(anyAddr);
		if (e == null) {
			return null;
		}
		ArrayList<IPv4Address> list = new ArrayList<>();
		for (ServerEntry tmp : e) {
			list.add(tmp.getPhyAddr());
		}
		return list;
	}
	
	/**
	 * Get the anycast IP address of the group which a server has previously
	 * subscribed at
	 * @param server The server that subscribed to the anycast group
	 * @return The anycast IP address of the group which a server subscribed at
	 */
	public IPv4Address getAnyGroup(ServerEntry server) {
		for (Entry e : reg) {
			if(e.servers.contains(server)) {
				return e.anyAddr;
			}
		}
		return null;
	}
	
	/**
	 * Get the anycast IP address of the group which a server has previously
	 * subscribed at
	 * @param phyAddr The physical IP address of the server
	 * @param port The port used by the service
	 * @return The anycast IP address of the group which a server subscribed at
	 */
	public IPv4Address getAnyGroup(IPv4Address phyAddr, short port) {
		ServerEntry entry = new ServerEntry(phyAddr, port);
		return getAnyGroup(entry);
	}
	
	/**
	 * Subscribe a server to a group with an anycast IP address equal to
	 * anyAddr
	 * @param anyAddr The anycast IP address to which a server has to be
	 * subscribed
	 * @param server The couple <physical IP address, port> of the server 
	 * @return true: if the subscription succeeds.
	 * false: the service is already subscribed
	 */
	public synchronized boolean subscribe(IPv4Address anyAddr, ServerEntry server) {
		// Look if the server has already been registered with the same port
		// in another anycast group
		if (this.contains(server)) {
			return false;
		}
		// Look if the service associated with anyAddr is already registered
		int i = reg.indexOf(new Entry(anyAddr, null));
		if (i != -1) {
			// The anycast group already exist, add the server
			reg.get(i).servers.add(server);
			return true;
		}
		// The anycast group has not been registered yet
		ArrayList<ServerEntry> servers = new ArrayList<>();
		servers.add(server);
		reg.add(new Entry(anyAddr, servers));
		return true;
	}
	
	/**
	 * Subscribe a server to a group with an anycast IP address equal to
	 * anyAddr
	 * @param anyAddr The anycast IP address to which a server has to be
	 * subscribed
	 * @param phyAddr The physical IP address of the server
	 * @param port The port used by the server
	 * @return true: if the subscription succeeds.
	 * false: the service is already subscribed
	 */
	public boolean subscribe (IPv4Address anyAddr, IPv4Address phyAddr, short port) {
		ServerEntry server = new ServerEntry(phyAddr, port);
		return subscribe(anyAddr, server);
	}
	
	/**
	 * Subscribe a list of servers to a group with an anycast IP address equal
	 * to anyAddr
	 * @param anyAddr The anycast IP address to which a server has to be
	 * subscribed
	 * @param servers The list of <physical IP address, port> of servers that
	 * has to be subscribedclone()
	 * @return The number of successful subscriptions
	 */
	public int subscribe(IPv4Address anyAddr, ArrayList<ServerEntry> servers){
		int count = 0;
		for (ServerEntry server : servers) {
			if (subscribe(anyAddr, server)) {
				count++;
			}
		}
		return count;
	}
	
	/**
	 * Subscribe a list of servers to a group with an anycast IP address equal
	 * to anyAddr
	 * @param anyAddr The anycast IP address to which a server has to be
	 * subscribed
	 * @param phyAddr The list of physical IP addresses of the servers that has
	 * to be subscribed
	 * @param ports The list of ports of the servers that has to be subscribed
	 * @return The number of successful subscriptions
	 */
	public int subscribe(IPv4Address anyAddr, ArrayList<IPv4Address> phyAddr, short[] ports){
		int size = phyAddr.size();
		if (size != ports.length) {
			return 0;
		}
		ArrayList<ServerEntry> servers = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			servers.add(new ServerEntry(phyAddr.get(i), ports[i]));
		}
		return subscribe(anyAddr, servers);
	}
	
	/**
	 * Unsubscribe a server belogning to a group with an anycast IP address
	 * equal to anyAddr
	 * @param anyAddr The anycast IP address representing the service that the
	 * server no longer offers
	 * @param server The couple <physical IP address, port> of the server 
	 * @return true: if the subscription is successfully cancelled.
	 * false: if the service was not subscribed to the specified anycast group
	 */
	public synchronized boolean unsubscribe(IPv4Address anyAddr, ServerEntry server) {
		ArrayList<ServerEntry> servers = new ArrayList<>();
		servers.add(server);
		// Look if the anycast group has been registered
		int i = reg.indexOf(new Entry(anyAddr, servers));
		if (i != -1) {
			// The server has been registered with the specified anycast group
			Entry e = reg.get(i);
			e.servers.remove(server);
			// If the anycast group has no server, delete the group
			if (e.servers.isEmpty()) {
				reg.remove(i);
			}
			return true;
		}
		// The specified anycast IP addres and server were not found
		return false;
	}
	
	/**
	 * Unsubscribe a server belogning to a group with an anycast IP address
	 * equal to anyAddr
	 * @param anyAddr The anycast IP address representing the service that the
	 * server no longer offers
	 * @param phyAddr The physical IP address of the server
	 * @param port The port used by the server
	 * @return true: if the subscription is successfully cancelled.
	 * false: if the service was not subscribed to the specified anycast group
	 */
	public boolean unsubscribe(IPv4Address anyAddr, IPv4Address phyAddr, short port) {
		ServerEntry server = new ServerEntry(phyAddr, port);
		return unsubscribe(anyAddr, server);
	}
	
	/**
	 * Unsubscribe a server belogning to a group with an anycast IP address
	 * equal to anyAddr
	 * @param anyAddr The anycast IP address representing the service that the
	 * server no longer offers
	 * @param servers The list of <physical IP address, port> of servers that
	 * no longer offer the service
	 * @return The number of successful cancelled subscriptions
	 */
	public int unsubscribe(IPv4Address anyAddr, ArrayList<ServerEntry> servers) {
		int count = 0;
		for (ServerEntry server : servers) {
			if (unsubscribe(anyAddr, server)) {
				count++;
			}
		}
		return count;
	}
	
	/**
	 * Unsubscribe a server belogning to a group with an anycast IP address
	 * equal to anyAddr
	 * @param anyAddr The anycast IP address representing the service that the
	 * server no longer offers
	 * @param phyAddr The list of physical IP address, port of servers that no
	 * longer offer the service
	 * @param ports The list of ports of the servers that has to cancel the
	 * subscription
	 * @return The number of successful cancelled subscriptions
	 */
	public int unsubscribe(IPv4Address anyAddr, ArrayList<IPv4Address> phyAddr, short[] ports){
		int size = phyAddr.size();
		if (size != ports.length) {
			return 0;
		}
		ArrayList<ServerEntry> servers = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			servers.add(new ServerEntry(phyAddr.get(i), ports[i]));
		}
		return unsubscribe(anyAddr, servers);
	}
	
	/**
	 * Get the servers which have subscribed to the anycast group offering a
	 * service on the specified port
	 * @param anyAddr The IP address of the anycast group
	 * @param port The port on wich the service has been bound
	 * @return The list of the physical IP address of the servers that offer
	 * the service on the specified port and anycast group
	 */
	public synchronized ArrayList<IPv4Address> getServicesByPort(IPv4Address anyAddr, short port) {
		// Look if the service associated with anyAddr is registered
		ArrayList<IPv4Address> list = new ArrayList<>();
		int i = reg.indexOf(new Entry(anyAddr, null));
		if (i != -1) {
			Entry e = reg.get(i);
			for (ServerEntry server : e.servers) {
				if (server.getPort() == port) {
					list.add(server.getPhyAddr());
				}
			}
		}
		return list;
	}
	
	@Override
	public String toString() {
		String list = new String();
		for (Entry e : reg) {
			list+=e.toString()+"  ";
		}
		return list;
	}
}
