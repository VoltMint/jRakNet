package xyz.vothmint.jraknet.datastructures;

import xyz.vothmint.jraknet.EncapsulatedPacket;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public interface OrderingHeap {

	void insert( long weight, EncapsulatedPacket packet );

	boolean isEmpty();

	EncapsulatedPacket peek();

	EncapsulatedPacket poll();

}
