package net.floodlightcontroller.loadbalancerproject;

import java.io.IOException;
import java.util.ArrayList;

import org.projectfloodlight.openflow.types.IPv4Address;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * It parses the POST message for removing subscription received from the network  
 * manager through the RESTEasy interface.
 * 
 * http://localhost:8080/lb/controller/unsubscribe/json
 * POST message
 * Data: Custom
 * MIME format: application/json
 * Message format: {"type":"unsubscribe", "anycast":"9.9.9.9", "physical":["10.0.0.1:1080", "10.0.0.2:1080"]}
 */
public class Unsubscribe extends ServerResource {
	@Post("application/json")
	public String store(String fmJson){
		if (fmJson == null)
			return new String("Error: no attributes");
		System.out.println();
		System.out.println(fmJson);
		ObjectMapper mapper = new ObjectMapper();
		IPv4Address anyAddr = null;
		ArrayList<ServerEntry> servers = new ArrayList<>();
		try {
			JsonNode root = mapper.readTree(fmJson);
			String type = root.get("type").asText();
			if (!type.equals("unsubscribe"))
				return new String("Error: invalid operation");
			anyAddr = IPv4Address.of(root.get("anycast").asText());
			JsonNode phyNode = root.get("physical");
			if (phyNode.isArray()) {
				for (JsonNode n : phyNode) {
					String[] tmp = n.asText().split(":");
					ServerEntry se = new ServerEntry(IPv4Address.of(tmp[0]), Short.parseShort(tmp[1]));
					servers.add(se);
				}
			} else { //only one element
				String[] tmp = phyNode.asText().split(":");
				ServerEntry se = new ServerEntry(IPv4Address.of(tmp[0]), Short.parseShort(tmp[1]));
				servers.add(se);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		//Invoke the correspondent function on the controller
		ILoadBalancerREST lb = (ILoadBalancerREST)getContext().getAttributes().get(ILoadBalancerREST.class.getCanonicalName());
		return lb.unsubscribe(anyAddr, servers);
	}
}
