package net.floodlightcontroller.loadbalancerproject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.projectfloodlight.openflow.types.IPv4Address;
import org.restlet.data.Form;
import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;

/**
 * It creates a response for the GET message that requires the physical addresses
 * associated to a given anycast address. 
 * The message is received from the network manager through the RESTEasy interface.
 * 
 * http://localhost:8080/lb/controller/showlist/json?anycast=9.9.9.9
 * GET message
 */
public class ShowList extends ServerResource {
	@Get("json")
	public Map<String, Object> retrieve() {
		Form form = getQuery();
		String anyAddr = form.getFirstValue("anycast", true);
		
		//Invoke the correspondent function on the controller
		ILoadBalancerREST lb = (ILoadBalancerREST)getContext().getAttributes().get(ILoadBalancerREST.class.getCanonicalName());
		ArrayList<IPv4Address> phyAddr = lb.showList(IPv4Address.of(anyAddr));
		
		//Build a response
		Map<String, Object> map = new HashMap<String, Object>();
		if (phyAddr==null)
			map.put(anyAddr, "No physical addresses associated");
		else {
			ArrayList<String> showPhyAddr = new ArrayList<>();
			for (IPv4Address a : phyAddr)
				showPhyAddr.add(a.toString());
			map.put(anyAddr, showPhyAddr);
		}
		return map;
	}
}
