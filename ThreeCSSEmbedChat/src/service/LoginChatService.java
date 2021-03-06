package service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.websocket.Session;

import action.ChatGroupAction;
import action.ChatGroupUserAction;
import action.UserAction;
import action.UserGroupAction;
import dao.model.base.ChatGroup;
import dao.model.base.ChatGroupUser;
import data.OnlineUser;
import data.OnlineUserManager;
import log.LogManager;
import msg.IMsgListener;
import msg.MsgOpCode;
import msg.MsgOpCodeChat;
import msg.MsgPacket;
import protobuf.http.UserGroupProto.UserData;
import protobuf.http.UserGroupProto.UserGroupData;
import protobuf.ws.LoginChatProto.AgainConnectS;
import protobuf.ws.LoginChatProto.ChatUserOfflineS;
import protobuf.ws.LoginChatProto.ChatUserOnlineS;
import protobuf.ws.LoginChatProto.LoginChatServerC;
import protobuf.ws.LoginChatProto.LoginChatServerS;
import thread.AsyncThreadManager;
import tool.StringUtil;
import websocket.IWSListener;
import ws.WsOpCodeChat;
import ws.WsPacket;

public class LoginChatService implements IWSListener, IMsgListener {

	@Override
	public Map<Integer, String> getWSs() throws Exception {
		HashMap<Integer, String> map = new HashMap<>();
		map.put(WsOpCodeChat.LOGIN_CHAT_SERVER_C, "LoginChatServerHandle");
		return map;
	}

	@Override
	public Map<String, String> getMsgs() throws Exception {
		HashMap<String, String> map = new HashMap<>();
		map.put(MsgOpCodeChat.USER_OFFLINE, "userOfflineHandle");
		map.put(MsgOpCode.WEBSOCKET_CLIENT_DISCONNECT, "webSocketCientDisConnectHandle");
		return map;
	}

	@Override
	public Object getInstance() {

		return this;
	}

	// 线程1的业务
	public void LoginChatServerHandle(WsPacket wsPacket) {
		LoginChatServerC builder1 = (LoginChatServerC) wsPacket.getData();
		String token = builder1.getToken();
		Session session = (Session) wsPacket.session;
		UserData userData = UserAction.getUser(token);
		if (userData == null) {
			// 获取用户数据为空
			return;
		}
		OnlineUser onlineUser = OnlineUserManager.getOnlineUserByUserId(userData.getUserId());
		if (onlineUser != null) {
			// 该用户在线，需要踢这个用户下线,才可以上线
			onlineUser.setKick(true);

			// 告诉客户端可重新走登陆请求
			AgainConnectS.Builder againBuilder = AgainConnectS.newBuilder();
			againBuilder.setWsOpCode(WsOpCodeChat.AGAIN_CONNECT_S);
			WsPacket againWsPacket = new WsPacket(WsOpCodeChat.AGAIN_CONNECT_S, againBuilder.build());
			try {
				session.getBasicRemote().sendObject(againWsPacket);
			} catch (Exception e) {
				LogManager.websocketLog.error("发送重新登录异常", e);
			}
			return;
		}

		// 获取好友列表
		List<UserData> friendList = null;
		// 聊天组列表
		List<ChatGroup> chatGroupList = null;
		if (!StringUtil.stringIsNull(userData.getUserGroupTopId())) {
			friendList = UserAction.getFriendList(userData.getUserGroupTopId(), token);
			// 好友列表不可能为空
			if (friendList == null || friendList.size() == 0) {
				return;
			}
			/****************************** 初始化公司群 ****************************************/
			List<ChatGroup> userGroupList = ChatGroupAction.getChatGroupList(userData.getUserGroupTopId());
			ChatGroup chatGroup = null;
			if (userGroupList == null || userGroupList.size() == 0) {
				UserGroupData userGroupData = UserGroupAction.getUserGroup(userData.getUserGroupTopId(), token);
				if (userGroupData == null) {
					return;
				}
				chatGroup = ChatGroupAction.createChatGroup(userGroupData.getUserGroupName(), userGroupData.getUserGroupId());
				if (chatGroup == null) {
					return;
				}
				for (int i = 0; i < friendList.size(); i++) {
					UserData frienduserData = friendList.get(i);
					ChatGroupUser chatGroupUser = ChatGroupUserAction.createChatGroupUser(frienduserData.getUserId(), chatGroup.getChatGroupId(), null, frienduserData.getUserRole());
					if (chatGroupUser == null) {
						LogManager.websocketLog.warn("用户id为" + frienduserData.getUserId() + "未加入公司群组");
					}
				}
			} else {
				chatGroup = userGroupList.get(0);
				List<ChatGroupUser> chatGroupUserList = ChatGroupUserAction.getChatGroupUserList(null, chatGroup.getChatGroupId());
				if (chatGroupUserList == null) {
					return;
				}
				if (friendList.size() > chatGroupUserList.size()) {
					// 插入新增的用户
					for (int i = 0; i < friendList.size(); i++) {
						UserData frienduserData = friendList.get(i);
						boolean isIn = false;
						for (int j = 0; j < chatGroupUserList.size(); j++) {
							ChatGroupUser chatGroupUser = chatGroupUserList.get(j);
							if (frienduserData.getUserId().equals(chatGroupUser.getUserId())) {
								// 提高遍历效率
								chatGroupUserList.remove(chatGroupUser);
								isIn = true;
								break;
							}
						}
						if (!isIn) {
							ChatGroupUser chatGroupUser = ChatGroupUserAction.createChatGroupUser(frienduserData.getUserId(), chatGroup.getChatGroupId(), null, frienduserData.getUserRole());
							if (chatGroupUser == null) {
								LogManager.websocketLog.warn("用户id为" + frienduserData.getUserId() + "未加入公司群组");
							}
						}
					}
				}
			}
			/****************************** 初始化公司群 ****************************************/
			chatGroupList = ChatGroupAction.getChatGroupListByUserId(userData.getUserId());
			if (chatGroupList == null) {
				// 公司群组初始化
			}
		}
		// 加入在线列表
		OnlineUser newOnlineUser = new OnlineUser(userData, session, token);
		boolean result = OnlineUserManager.addOnlineUser(newOnlineUser);
		if (!result) {
			// 这种情况不可能发生
			return;
		}
		LoginChatServerS.Builder builder = LoginChatServerS.newBuilder();
		builder.setWsOpCode(WsOpCodeChat.LOGIN_CHAT_SERVER_S);
		builder.setChatUser(UserAction.getChatUserDataBuilder(userData, true));
		if (friendList != null) {
			for (int i = 0; i < friendList.size(); i++) {
				UserData friendUserData = friendList.get(i);
				if (friendUserData.getUserId().equals(userData.getUserId())) {
					// 排除自己
					continue;
				}
				boolean isOnline = false;
				OnlineUser friendOnlineUser = OnlineUserManager.getOnlineUserByUserId(friendUserData.getUserId());
				if (friendOnlineUser != null) {
					isOnline = true;
				}
				builder.addChatUserList(UserAction.getChatUserDataBuilder(friendUserData, isOnline));
			}
		}
		if (chatGroupList != null) {
			for (int i = 0; i < chatGroupList.size(); i++) {
				ChatGroup chatGroup = chatGroupList.get(i);
				builder.addChatGroupList(ChatGroupAction.getChatGroupDataBuilder(chatGroup));
			}
		}
		WsPacket sendWsPacket = new WsPacket(WsOpCodeChat.LOGIN_CHAT_SERVER_S, builder.build());
		boolean sendResult = newOnlineUser.send(sendWsPacket);
		if (!sendResult) {
			// 发送登陆抛异常，直接移出在线列表，不走下先流程了算登录失败
			OnlineUserManager.removeOnlineUser(newOnlineUser);
			return;
		}
		ChatUserOnlineS.Builder chatUserOnlineBuilder = ChatUserOnlineS.newBuilder();
		chatUserOnlineBuilder.setWsOpCode(WsOpCodeChat.USER_ONLINE_S);
		chatUserOnlineBuilder.setChatUser(UserAction.getChatUserDataBuilder(userData, true));
		WsPacket sendOnlineWsPacket = new WsPacket(WsOpCodeChat.USER_ONLINE_S, chatUserOnlineBuilder.build());
		// 广播给在线好友上线
		if (friendList != null) {
			for (int i = 0; i < friendList.size(); i++) {
				UserData friendUserData = friendList.get(i);
				if (friendUserData.getUserId().equals(userData.getUserId())) {
					// 排除自己
					continue;
				}
				OnlineUser friendOnlineUser = OnlineUserManager.getOnlineUserByUserId(friendUserData.getUserId());
				if (friendOnlineUser == null) {
					continue;
				}
				friendOnlineUser.send(sendOnlineWsPacket);
			}
		}
		// 进入随机线程
		int[] threadPriority = AsyncThreadManager.getRandomThreadPriority();
		newOnlineUser.setThreadPriority(threadPriority);
		AsyncThreadManager.addChangeCycle(newOnlineUser, threadPriority[0], threadPriority[1]);
	}

	// 线程1的业务
	public void userOfflineHandle(MsgPacket msgPacket) {
		OnlineUser onlineUser = (OnlineUser) msgPacket.getOtherData();
		List<UserData> friendList = null;
		if (!StringUtil.stringIsNull(onlineUser.getUserData().getUserGroupTopId())) {
			friendList = UserAction.getFriendList(onlineUser.getUserData().getUserGroupTopId(), onlineUser.getToken());
			if (friendList != null && friendList.size() != 0) {
				ChatUserOfflineS.Builder chatUserOfflineSBuilder = ChatUserOfflineS.newBuilder();
				chatUserOfflineSBuilder.setWsOpCode(WsOpCodeChat.USER_OFFLINE_S);
				chatUserOfflineSBuilder.setUserId(onlineUser.getUserId());
				WsPacket sendOfflineWsPacket = new WsPacket(WsOpCodeChat.USER_OFFLINE_S, chatUserOfflineSBuilder.build());
				// 广播给在线好友上线
				for (int i = 0; i < friendList.size(); i++) {
					UserData friendUserData = friendList.get(i);
					if (friendUserData.getUserId().equals(onlineUser.getUserId())) {
						// 排除自己
						continue;
					}
					OnlineUser friendOnlineUser = OnlineUserManager.getOnlineUserByUserId(friendUserData.getUserId());
					if (friendOnlineUser == null) {
						continue;
					}
					friendOnlineUser.send(sendOfflineWsPacket);
				}
			}
		}
		OnlineUserManager.removeOnlineUser(onlineUser);
		onlineUser.clear();
	}

	// 线程1的业务
	public void webSocketCientDisConnectHandle(MsgPacket msgPacket) {
		Session session = (Session) msgPacket.getOtherData();
		OnlineUser onlineUser = OnlineUserManager.getOnlineUserBySessionId(session.getId());
		if (onlineUser != null) {
			onlineUser.setDisConnect(true);
		} else {
			LogManager.websocketLog.info("该链接未登陆，不用走下线流程或者是主动下线");
		}

	}

}
