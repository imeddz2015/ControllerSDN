package net.floodlightcontroller.loadbalancerproject;

import org.projectfloodlight.openflow.types.IPv4Address;

public class ServerEntry {
	private IPv4Address phyAddr;
	private short port;
	
	public ServerEntry(IPv4Address phyAddr, short port) {
		this.phyAddr = phyAddr;
		this.port = port;
	}
	
	public IPv4Address getPhyAddr() {
		return phyAddr;
	}
	
	public short getPort() {
		return port;
	}
	
	@Override
	public String toString() {
		return phyAddr.toString() + ":" + port;
	}
	
	@Override
	public boolean equals(Object o) {
		return this.phyAddr.equals(((ServerEntry)o).phyAddr) && this.port == ((ServerEntry)o).port;
	}
}
