package org.tfv.deskflow.data.aidl;

parcelable ServerState {
	String address;
	int port;
	boolean useTls;
	boolean autoDiscover;
}
