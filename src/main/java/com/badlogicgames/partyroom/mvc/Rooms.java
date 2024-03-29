package com.badlogicgames.partyroom.mvc;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the creation and update of rooms. Returned rooms
 * are read only.
 * 
 * The synching is a bit meh in this class. We try to avoid
 * some race conditions while locking at a very fine-grained level.
 */
public class Rooms {	
	private final Map<String, Room> rooms = new ConcurrentHashMap<>();
	private final String youtubeKey;
	
	public Rooms(String youtubeKey) {
		this.youtubeKey = youtubeKey;
		final Thread t = new Thread(() -> {
			while(true) {
				synchronized(rooms) {
					for(Entry<String, Room> entry: rooms.entrySet()) {						
						if(entry.getValue() != null && entry.getValue().users.size() == 0) {
							rooms.remove(entry.getKey());
							System.out.println("removed room " + entry.getKey());
						}
					}
				}
				
				try {
					Thread.sleep(10000);
				} catch (Exception e) {
				}
			}
		});
		t.setDaemon(true);
		t.start();
	}
	
	/**
	 * Creates a new room for the given user, or lets the given
	 * user join the room.
	 * 
	 * @return the created/joined room
	 */
	public Room join(String roomName, User user) {
		Objects.requireNonNull(roomName, "room name must not be null");
		Objects.requireNonNull(user, "user must not be null");
		
		Room room = null;
		synchronized(rooms) {
			room = rooms.get(roomName);
			if(room == null) {
				room = new Room();
				room.youtubeKey = youtubeKey;
				room.name = roomName;
				rooms.put(roomName, room);
			}			
		}
		
		boolean found = false;
		for(User otherUser: room.users) {
			if(otherUser.name.equals(user.name)) {
				found = true;
				break;
			}
		}
		if(!found) {
			leave(user);
			user.roomName = roomName;
			synchronized(room) {
				room.users.add(user);
			}
		}
		return room;
	}
	
	/**
	 * Removes the user from any room she's currently in.
	 */
	public void leave(User user) {
		user.song = null;
		if(user.roomName != null) {
			Room oldRoom = rooms.get(user.roomName);
			if(oldRoom != null) {
				synchronized(oldRoom) {
					oldRoom.users.remove(user);
					if(oldRoom.users.size() == 0) {
						synchronized(rooms) {
							rooms.remove(oldRoom.name);
						}
					}
				}
			}
		}
	}
	
	/**
	 * Returns the current status of the room
	 */
	public Room getStatus(String roomName) {
		Objects.requireNonNull(roomName, "room name may not be null");

		Room room = rooms.get(roomName);
		synchronized(room) {
			if(room.switchTime < System.nanoTime()) {
				room.currentUser++;
				if(room.currentUser >= room.users.size()) room.currentUser = 0;
				room.currentSong = room.users.get(room.currentUser).song;
				room.users.get(room.currentUser).song = null;
				room.startTime = room.currentSong == null? 0: new Date().getTime();
				if(room.currentSong != null) {
					room.switchTime = System.nanoTime() + room.currentSong.duration * 1000000000l + 4000000000l;
				} else {
					room.switchTime = System.nanoTime() + 1000000000;
				}
				System.out.println("switched song: " + room.currentSong);
			}
		}
		return room;
	}
	
	/**
	 * Adds a new message from the given user to the room's chat log
	 */
	public Room sendMessage(String roomName, String message, User user) {
		Objects.requireNonNull(roomName, "room name may not be null");
		Objects.requireNonNull(message, "message may not be null");
		Objects.requireNonNull(user, "user may not be null");
		
		Room room = rooms.get(roomName);
		Objects.requireNonNull(room, "room with name '" + roomName + "' doesn't exist");		
		Message msg = new Message();
		msg.message = message;
		msg.utcTimeStamp = new Date().getTime();
		msg.userName = user.name;
		room.messages.add(msg);
		return room;
	}
	
	/**
	 * Returns a list of room names and number of users in each room
	 */
	public List<RoomDescriptor> listRooms() {
		List<RoomDescriptor> descriptors = new ArrayList<>();
		for(Map.Entry<String, Room> entry: rooms.entrySet()) {
			RoomDescriptor desc = new RoomDescriptor();
			desc.name = entry.getKey();
			desc.numUsers = entry.getValue().users.size();
			descriptors.add(desc);
		}
		return descriptors;
	}

	/**
	 * Sets the song to be played for the next user. Synchs on the room
	 * the user is in, hence why it's in this class.
	 */
	public void setSong (User user, Item song) {
		Objects.requireNonNull(user, "user may not be null");
		
		Room room = rooms.get(user.roomName);
		synchronized(room) {
			user.song = song;
		}
	}
}
