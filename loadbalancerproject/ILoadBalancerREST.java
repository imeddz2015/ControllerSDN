package net.floodlightcontroller.loadbalancerproject;

import java.util.ArrayList;

import org.projectfloodlight.openflow.types.IPv4Address;

import net.floodlightcontroller.core.module.IFloodlightService;

/**
 * The interface shows what are the methods that can be invoked by the classes
 * that handle the communication with the RESTEasy interface. 
 * These methods are implemented by the controller.
 */
public interface ILoadBalancerREST extends IFloodlightService {
	
	public String subscribe(IPv4Address anyAddr, ArrayList<ServerEntry> phyAddr);
	public String unsubscribe(IPv4Address anyAddr, ArrayList<ServerEntry> phyAddr);
	public ArrayList<IPv4Address> showList(IPv4Address anyAddr);
	
}
