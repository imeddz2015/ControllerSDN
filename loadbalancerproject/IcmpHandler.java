package net.floodlightcontroller.loadbalancerproject;

import java.util.ArrayList;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.customforwarding.Forwarding;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.util.FlowModUtils;

public class IcmpHandler {
	
	public static boolean handle(Ethernet eth, IOFSwitch sw,
			FloodlightContext cntx, OFPacketIn packetIn, Forwarding forwarding) {
		
		ArrayList<IPv4Address> candidateDests =
				LoadBalancer.servReg.getServersPhyAddr(
						((IPv4)eth.getPayload()).getDestinationAddress());
		if(candidateDests == null) {	//The destination is an unicast address
			return toUnicast(eth, sw, cntx, packetIn, forwarding);
		}
		else {
			//The destination is a registered anycast address
			return toAnycast(cntx, eth, candidateDests, sw, packetIn);
		}
	}
	
	private static boolean toAnycast(FloodlightContext cntx, Ethernet eth,
			ArrayList<IPv4Address> phys, IOFSwitch sw, OFPacketIn packetIn) {

		IPv4 ipv4 = (IPv4)eth.getPayload();
		ICMP icmp = (ICMP)ipv4.getPayload();
		//Check if the ICMP message is a Echo request
		if (icmp.getIcmpType() == (byte)7 || icmp.getIcmpType() == (byte)8) {
			IPv4Address dest = RouteHandler.getNearestNotBusyServer(phys,
					IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE),
					sw.getId()
			);
			//Respond with an echo reply
			//Get Identifier and Sequence Number of the Echo request 
			//L4 packet
			ICMP l4 = new ICMP();
			if (dest != null) {
				//A server is available in the anycast group
				l4.setIcmpCode((byte)0);
			} else {
				//All the server are busy in the anycast group
				//Set Code Destination Unreachable
				l4.setIcmpCode((byte)3);
			}
			//The Type is 0 for both destination reachable and destination
			//unreachable (Type 0 = Net Unreachable)
			l4.setIcmpType((byte)0)
				//Set the same request payload (Identifier and Sequence Number)
				.setPayload(icmp.getPayload());
			// L2 packet
			IPacket l2 = new Ethernet()
				.setSourceMACAddress(MacAddress.of(sw.getId()))
				.setDestinationMACAddress(eth.getSourceMACAddress())
				.setEtherType(EthType.IPv4)
				.setPayload(new IPv4()
						.setSourceAddress(ipv4.getDestinationAddress())
						.setDestinationAddress(ipv4.getSourceAddress())
						.setTtl((byte) 64)
						.setProtocol(IpProtocol.ICMP)
						.setPayload(l4));
			
			// Create the Packet-Out and set basic data for it (buffer id and in port)
			OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
			pob.setBufferId(OFBufferId.NO_BUFFER)
				.setInPort(OFPort.ANY);
			
			// Create action -> send the packet back from the source port
			ArrayList<OFAction> actions = new ArrayList<OFAction>();
			
			actions.add(sw.getOFFactory().actions().buildOutput()
					.setPort(packetIn.getMatch().get(MatchField.IN_PORT))
					.setMaxLen(0xffFFffFF)
					.build()
			);
			// Assign the action
			pob.setActions(actions);
			
			/* If the switch doens't support buffering set the buffer ID to none,
			otherwise it'll be the the buffer ID of the PacketIn*/
			if (sw.getBuffers() == 0) {
				packetIn = packetIn.createBuilder().setBufferId(OFBufferId.NO_BUFFER).build();
				pob.setBufferId(OFBufferId.NO_BUFFER);
			} else {
				pob.setBufferId(packetIn.getBufferId());
			}
			
			pob.setInPort(OFPort.ANY);
			// Set the ICMP echo reply as packet data 
			pob.setData(l2.serialize());
			
			sw.write(pob.build());
		}
		return true;
	}
	
	private static boolean toUnicast(Ethernet eth, IOFSwitch sw, FloodlightContext cntx, 
			OFPacketIn packetIn, Forwarding forwarding) {
		System.out.println("ICMP handler " +
				((IPv4)eth.getPayload()).getSourceAddress() + " -> " +
				((IPv4)eth.getPayload()).getDestinationAddress() + " on switch " + sw.getId());
		IPv4Address srcAddr = ((IPv4)eth.getPayload()).getSourceAddress();
		IPv4Address dstAddr = ((IPv4)eth.getPayload()).getDestinationAddress();
		
		Path route = RouteHandler.getFastestRoute(dstAddr,
				IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE),
				sw.getId());
		if (route == null) {
			return false;
		}
		OFPort outPort = RouteHandler.getOutPort(dstAddr, route, sw.getId());
		// Create a flow table modification to add a rule
		OFFlowAdd.Builder fmb = sw.getOFFactory().buildFlowAdd();
		fmb.setIdleTimeout(LoadBalancer.IDLE_TIMEOUT)
			.setHardTimeout(LoadBalancer.HARD_TIMEOUT)
			.setBufferId(OFBufferId.NO_BUFFER)
			.setCookie(U64.of(0))
			.setPriority(FlowModUtils.PRIORITY_MAX);
		// Create the match
		Match.Builder mb = sw.getOFFactory().buildMatch()
				.setExact(MatchField.ETH_TYPE, EthType.IPv4)
				.setExact(MatchField.IP_PROTO, IpProtocol.ICMP)
				.setExact(MatchField.IPV4_DST, dstAddr)
				.setExact(MatchField.IPV4_SRC, srcAddr);
		// Create the action associated with the match
		ArrayList<OFAction> actionList = new ArrayList<OFAction>();
		OFActionOutput setDstPort = sw.getOFFactory().actions().buildOutput()
			    .setMaxLen(0xFFffFFff)
			    .setPort(outPort)
			    .build();
		actionList.add(setDstPort);
		
		RouteHandler.pushRoute(dstAddr, route, mb.build(), fmb);
		
		// Forward the packet containing the ICMP message
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		pob.setBufferId(packetIn.getBufferId());
		pob.setInPort(packetIn.getMatch().get(MatchField.IN_PORT));
		pob.setData(packetIn.getData());
		// Assign the actions to the packet
		pob.setActions(actionList);
		sw.write(pob.build());
		return true;
	}
}
