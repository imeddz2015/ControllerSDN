package net.floodlightcontroller.loadbalancerproject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.VlanVid;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.util.FlowModUtils;

public class TcpUdpHandler {
public static boolean handle(Ethernet eth, IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		
		IPv4 ipv4 = (IPv4) eth.getPayload();
		
		TransportPort srcPort = ipv4.getProtocol() == IpProtocol.UDP ?
				((UDP)(ipv4.getPayload())).getSourcePort():
				((TCP)(ipv4.getPayload())).getSourcePort();
				
		/* FROM ANYCAST TO UNICAST */
		IPv4Address anyGroup = LoadBalancer.servReg.getAnyGroup(
				ipv4.getSourceAddress(), (short)(srcPort.getPort()));
		if (anyGroup != null) {
			toUnicast(anyGroup, srcPort, eth, sw, pi, cntx);
			return true;
		}
		
		/* FROM UNICAST TO ANYCAST */
		ArrayList<IPv4Address> phys = LoadBalancer.servReg.getServersPhyAddr(
				ipv4.getDestinationAddress());
		if (phys!=null) {
			return toAnycast(phys, eth, sw, pi, cntx);
		}
		
		/* FROM UNICAST TO UNICAST */
		return false;
	}
	
	/**
	 * Handle messages from an unicast address to an anycast address
	 * @param eth The packet received
	 * @param sw The switch that received the packet
	 * @param pi The OpenFlow packet
	 * @param cntx The Floodlight context
	 * @return
	 */
	public static boolean toAnycast(ArrayList<IPv4Address> phys, Ethernet eth, IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		
		// The physical unicast address chosen instead of the anycast address
		IPv4Address dstIp = null;
		// The route from the current switch to addr
		Path route = null;
		synchronized (ServiceCounter.class) {
			// Find all the routes to reach the servers whose addresses are in phys
			ArrayList<RouteInfo> routes =
					RouteHandler.findAllRoutes(
							phys,
							IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE),
							sw.getId());
			Iterator<RouteInfo> entries = routes.iterator();
			// The routes are sorted based on the number of hops needed to reach a destination
			while (entries.hasNext()) {
				RouteInfo entry = entries.next();
				// Check if the destination is available
				if (LoadBalancer.servCounter.isAvailable(entry.destination)) {
					System.out.printf("Server %s is available\n", entry.destination);
					dstIp = entry.destination;
					route = entry.path;
					LoadBalancer.servCounter.newService(dstIp);
					break;
				}
			}
		}

		if (dstIp == null) {
			System.out.println("No server is available!");
			return true;
		}

		/* SEND THE PACKET TO THE PHYSICAL SERVER */
		
		// Create the actions associated with the incoming packet:
		// - Set as destination IP address the unicast address of the available
		//   server
		// - Set as destination MAC address the MAC of the available server
		// - Set as output port the swich port found by RouteFinder
        OFActions actions = sw.getOFFactory().actions();
        ArrayList<OFAction> actionList = new ArrayList<OFAction>();
        OFOxms oxms = sw.getOFFactory().oxms();
        // Set dstIp as the destination address for the incoming packet
        OFActionSetField setDstIp = actions.buildSetField()
        	    .setField(
        	        oxms.buildIpv4Dst()
        	        .setValue(dstIp)
        	        .build()
        	    ).build();
        actionList.add(setDstIp);
		
        // Find the MAC of the available server
        Iterator<? extends IDevice> dstDev = LoadBalancer.deviceManagerService.queryDevices(MacAddress.NONE, VlanVid.ZERO, dstIp, IPv6Address.NONE, DatapathId.NONE, OFPort.ZERO);
        if(dstDev.hasNext()) {
        	// Set the MAC of the server as destination MAC address
        	IDevice device = dstDev.next();
        	OFActionSetField setDestMAC = actions.buildSetField()
        			.setField(
        					oxms.buildEthDst()
        					.setValue(device.getMACAddress())
        					.build()
        					).build();
        	actionList.add(setDestMAC);
        }
		
        // Set the output port
        OFActionOutput setDstPort = sw.getOFFactory().actions().buildOutput()
			    .setMaxLen(0xFFffFFff)
			    .setPort(RouteHandler.getOutPort(dstIp, route, sw.getId()))
			    .build();
		actionList.add(setDstPort);
        
        // Create the packet to send to the available server
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut()
				.setBufferId(pi.getBufferId())
				.setInPort(pi.getMatch().get(MatchField.IN_PORT))
		// Assign the actions to the packet
				.setActions(actionList);
		/* Packet might be buffered in the switch or encapsulated in Packet-In
		   If the packet is encapsulated in Packet-In sent it back */
		if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
            pob.setData(pi.getData());
		} 

		/* In the following switches (if any), push the rules to send the
		   packet towards dstIp */
		Path newRoute = cutRouteHead(route);
		if (newRoute != null) {
			// Create a flow table modification to add a rule
			OFFlowAdd.Builder flowMod = sw.getOFFactory().buildFlowAdd()
					.setIdleTimeout(LoadBalancer.IDLE_TIMEOUT)
					.setHardTimeout(LoadBalancer.HARD_TIMEOUT)
					.setBufferId(OFBufferId.NO_BUFFER)
					.setCookie(U64.of(0))
					.setPriority(FlowModUtils.PRIORITY_MAX);
			// Create the match
			Match.Builder match = sw.getOFFactory().buildMatch()
					.setExact(MatchField.ETH_TYPE, EthType.IPv4)
					.setExact(MatchField.IPV4_DST, dstIp);
			RouteHandler.pushRoute(dstIp, newRoute, match.build(), flowMod);
		}

		sw.write(pob.build());
		return true;
	}
	/**
	 * Send a packet from a server registered in an anycast group to a client
	 * which is expecting an answer from an anycast address
	 * @param anycast The anycast address of the group the server is belonging to
	 * @param srcPort The port used in the transport protocol
	 * @param eth The packet received
	 * @param sw The switch that received the packet
	 * @param pi The OpenFlow message
	 * @param cntx The Floodlight context
	 */
	public static void toUnicast(IPv4Address anycast, TransportPort srcPort, Ethernet eth, IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		IPv4 ipv4 = (IPv4)eth.getPayload();
		IPv4Address dstIp = ipv4.getDestinationAddress();
		// Find the fastest route to get to the client
		Path route = RouteHandler.getFastestRoute(dstIp, 
				IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE),
				sw.getId());
		
		// Create a flow table modification message to add a rule
		OFFlowAdd.Builder flowMod = sw.getOFFactory().buildFlowAdd()
				.setIdleTimeout(LoadBalancer.IDLE_TIMEOUT)
				.setHardTimeout(LoadBalancer.HARD_TIMEOUT)
				.setBufferId(OFBufferId.NO_BUFFER)
				.setCookie(U64.of(0))
				.setPriority(FlowModUtils.PRIORITY_MAX);
		
		// Create the match with the anycast source address and the port
		Match.Builder match = sw.getOFFactory().buildMatch()
				.setExact(MatchField.ETH_TYPE, EthType.IPv4)
				.setExact(MatchField.IPV4_SRC, ipv4.getSourceAddress());
		if (ipv4.getProtocol() == IpProtocol.TCP) {
			match.setExact(MatchField.IP_PROTO, IpProtocol.TCP)
				.setExact(MatchField.TCP_SRC, srcPort);
		} else {
			match.setExact(MatchField.IP_PROTO, IpProtocol.UDP)
				.setExact(MatchField.UDP_SRC, srcPort);
		}

		// Create the actions associated with the incoming packet:
		// - Swap the physical source address with the anycast address
		// - Swap the MAC address of the server with a MAC address
		//   associated with the anycast group
		// - Set as output port the one found with RouteFinder
		OFActions actions = sw.getOFFactory().actions();
		ArrayList<OFAction> actionList = new ArrayList<OFAction>();
		OFOxms oxms = sw.getOFFactory().oxms();
		// Set as source of the packet the anycast address
		OFActionSetField setSrcIp = actions.buildSetField()
				.setField(
					oxms.buildIpv4Src()
			        .setValue(anycast)
			        .build()
			    ).build();
		actionList.add(setSrcIp);
		// Set the source MAC address
		OFActionSetField setDstMAC = actions.buildSetField()
			    .setField(
				oxms.buildEthSrc()
				.setValue(
						eth.getSourceMACAddress().equals(LoadBalancer.anycastMacAddress[0])?
				 			LoadBalancer.anycastMacAddress[1] :
				 			LoadBalancer.anycastMacAddress[0]
				)
				.build()
			    ).build();
		actionList.add(setDstMAC);
		// Send the packet through the port in order to follow the right route
		OFActionOutput output = actions.buildOutput()
			    .setMaxLen(0xFFffFFff)
			    .setPort(RouteHandler.getOutPort(dstIp, route, sw.getId()))
			    .build();
		actionList.add(output);
		
		
		OFInstructionApplyActions applyActions = sw.getOFFactory().instructions().buildApplyActions()
			    .setActions(actionList)
			    .build();
		ArrayList<OFInstruction> instructionList = new ArrayList<OFInstruction>();
		instructionList.add(applyActions);
		
		// Apply the match and the actions associated with such match
		flowMod.setInstructions(instructionList)
				.setMatch(match.build());
		sw.write(flowMod.build());
		
		/* In the following switches (if any), push the rules to send the
		   packet towards the client */
		Path newRoute = cutRouteHead(route);
		if (newRoute != null) {
			match.setExact(MatchField.IPV4_SRC, anycast);
			RouteHandler.pushRoute(dstIp, route, match.build(), flowMod);
		}
		
		// Create the packet to send to the client
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut()
				.setBufferId(pi.getBufferId())
				.setInPort(pi.getMatch().get(MatchField.IN_PORT))
		// Assign the action
				.setActions(actionList);
		/* Packet might be buffered in the switch or encapsulated in Packet-In 
		   If the packet is encapsulated in Packet-In sent it back */
		if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
            pob.setData(pi.getData());
		}

		sw.write(pob.build());	
		return;
	}
	
	/**
	 * The switches at the beginning of the path need to install different
	 * rules in their Flow Table. The path that involves the following switches
	 * is returned by this function: if the Path object has this structure
	 * || sw1, outport || sw2, inport || sw2, outport || ...
	 * || sw(n-1), inport || sw(n-1), outport || sw(n), inport ||
	 * then the returned Path is
	 * || sw2, outport || ...
	 * || sw(n-1), inport || sw(n-1), outport || sw(n), inport ||
	 * @param route The Path object whose head has to be cut
	 * @return The cut route
	 */
	private static Path cutRouteHead(Path route) {
		Path newRoute = null;
		List<NodePortTuple> list = route.getPath();
		if (list.size() > 1) {
			Iterator<NodePortTuple> iterator = list.iterator();
			iterator.next();
			NodePortTuple entry = iterator.next();
			newRoute = new Path(entry.getNodeId(), route.getId().getDst());
			ArrayList<NodePortTuple> temp = new ArrayList<>();
			while(iterator.hasNext()) {
				temp.add(iterator.next());
			}
			newRoute.setPath(temp);
		}
		return newRoute;
	}
}
