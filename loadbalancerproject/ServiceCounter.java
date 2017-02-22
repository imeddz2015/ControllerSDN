package net.floodlightcontroller.loadbalancerproject;

import java.util.ArrayList;
import java.util.Date;

import org.projectfloodlight.openflow.types.IPv4Address;

/**
 * The class handles the data structure that counts the number of requests received 
 * by a certain host
 */
public class ServiceCounter {
	//A server can receive at most MAX_COUNT/TIME_INTERVAL requests 
	private final long TIME_INTERVAL = 5 * 1000; //expressed in milliseconds
	private final int MAX_COUNT = 10;
	
	/**
	 * The basic component of the ServiceCounter class. It contains the IP
	 * address of a physical host with an associated request counter and a
	 * timestamp of the oldest request within the time interval.
	 */
	private class Entry {
		public IPv4Address phyAddr;
		public long timestamp;
		public int counter;
		
		public Entry(IPv4Address phyAddr, long timestamp) {
			this.phyAddr = phyAddr;
			this.timestamp = timestamp;
			this.counter = 1;
		}
		
		/**
		 * An entry o is equal to the current entry if its physical addresses
		 * is equal to the physical address of the current entry.
		 */
		@Override
		public boolean equals(Object o) {
			if (((Entry)o).phyAddr == this.phyAddr) {
				return true;
			}
			return false;
		}
		
		@Override
		public String toString() {
			return "<" + phyAddr.toString() + ", " + new Date(timestamp) + ", " + counter + ">";
		}
	}
	
	private ArrayList<Entry> list;
	
	public ServiceCounter() {
		list = new ArrayList<>();
	}
	
	/**
	 * Register a new request-for-service addressed to a specific server with
	 * its physical address equal to phyAddr. A server can accept maximum
	 * MAX_COUNT requests within a time interval TIME_INTERVAL milliseconds
	 * long
	 * @param phyAddr The physical IP address of the server which the request
	 * is addressed to
	 * @return true: if the request can be accepted by the server.
	 * false: if the server is too busy to accept the request
	 */
	public boolean newService(IPv4Address phyAddr) {
		Entry newEntry = new Entry(phyAddr, new Date().getTime());
		int i = list.indexOf(newEntry);
		if (i != -1) {
			Entry toUpdate = list.get(i);
			if (newEntry.timestamp - toUpdate.timestamp < TIME_INTERVAL) {
				if (toUpdate.counter < MAX_COUNT) {
					toUpdate.counter++;
					return true;
				}
				return false;
			}
			toUpdate.timestamp = newEntry.timestamp;
			toUpdate.counter = 1;
			return true;
		}
		list.add(newEntry);
		return true;
	}
	
	/**
	 * This function tells if the server can accept other requests
	 * @param phyAddr The physical IP address of the server
	 * @return true: the server can accept other requests.
	 * false: the server is busy and cannot accept other requests
	 */
	public boolean isAvailable(IPv4Address phyAddr) {
		Entry newEntry = new Entry(phyAddr, new Date().getTime());
		int i = list.indexOf(newEntry);
		if (i != -1) {
			Entry e = list.get(i);
			if (newEntry.timestamp - e.timestamp < TIME_INTERVAL && e.counter == MAX_COUNT) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Return the number of request that a server received within TIME_INTERVAL
	 * @param phyAddr The physical IP address of the server
	 * @return -1 if the physical IP address is not in the register.
	 * The number of request that a server received within TIME_INTERVAL
	 */
	public int getRequestCounter(IPv4Address phyAddr) {
		Entry newEntry = new Entry(phyAddr, new Date().getTime());
		int i = list.indexOf(newEntry);
		if (i != -1) {
			Entry e = list.get(i);
			if (newEntry.timestamp - e.timestamp < TIME_INTERVAL) {
				return e.counter;
			}
			e.counter = 0;
			return e.counter;
		}
		return -1;
	}
}
