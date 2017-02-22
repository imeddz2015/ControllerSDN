package net.floodlightcontroller.loadbalancerproject;

import java.util.ArrayList;
import java.util.Collections;

import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;

public class ArpHandler {
	public static void handle(Ethernet eth, IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
 		
		if (! (eth.getPayload() instanceof ARP)){
	 		return;
		}
		
 		ARP arpRequest = (ARP) eth.getPayload();
 		
 		ArrayList<IPv4Address> physicalAddress = LoadBalancer.servReg.getServersPhyAddr(arpRequest.getTargetProtocolAddress());
 		if (physicalAddress == null) { 			
 			return;
 		}
 		
 		//ARP request for a broadcast address
 		if (eth.getDestinationMACAddress().equals(MacAddress.BROADCAST)) {
 			
 			MacAddress targetMAC;
	 		if (eth.getSourceMACAddress().equals(LoadBalancer.anycastMacAddress[0]))
	 			targetMAC  = LoadBalancer.anycastMacAddress[1];
	 		else
	 			targetMAC = LoadBalancer.anycastMacAddress[0];
 		
	 		//Generate ARP reply
			IPacket arpReply = new Ethernet()
					.setSourceMACAddress(targetMAC)
					.setDestinationMACAddress(eth.getSourceMACAddress())
					.setEtherType(EthType.ARP)
					.setPriorityCode(eth.getPriorityCode())
					.setPayload(new ARP()
							.setHardwareType(ARP.HW_TYPE_ETHERNET)
							.setProtocolType(ARP.PROTO_TYPE_IP)
							.setHardwareAddressLength((byte) 6)
							.setProtocolAddressLength((byte) 4)
							.setOpCode(ARP.OP_REPLY)
							.setSenderHardwareAddress(targetMAC) 
							.setSenderProtocolAddress(arpRequest.getTargetProtocolAddress()) 
							.setTargetHardwareAddress(arpRequest.getSenderHardwareAddress())
							.setTargetProtocolAddress(arpRequest.getSenderProtocolAddress()));
		
			OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
			pob.setBufferId(OFBufferId.NO_BUFFER);
			
			OFActionOutput.Builder actionBuilder = sw.getOFFactory().actions().buildOutput();
			actionBuilder.setPort(pi.getMatch().get(MatchField.IN_PORT)); 
			
			pob.setActions(Collections.singletonList((OFAction)actionBuilder.build()));
			
			// Set the ARP reply as packet data 
			byte[] packetData = arpReply.serialize();
			pob.setData(packetData);
			
			sw.write(pob.build());
 		}
 		return;
	}
}
