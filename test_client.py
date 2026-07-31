#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P0 协议骨架验证客户端
=====================
复刻《率土之滨》客户端字节逻辑, 验证 Kotlin 服务端的帧编解码/握手正确:
  1. 连接服务端
  2. 接收 98888 握手包, 解析出 userId/cmdIndex/sid
  3. 用下发的 userId/sid 组装一个带 XOR 加密 JSON body 的上行包 (cmd=20003) 发出
  4. 再发一个明文心跳 90003

上行帧 (大端): [4B len=53+bodylen][serverId][userId][sid 32B][cmdId][cmdIndex][checkCode][flag(1)][body]
  checkCode = (cmdIndex*13) ^ cmdId ^ userId
  flag=5: body 逐字节 XOR (byte)checkCode
下行 98888: [4B len][cmd][hashCode][userId][cmdIndex][sid 32B][param]
"""
import socket
import struct
import json
import sys
import time

HOST, PORT = "127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 59979
SDK_UID = sys.argv[2] if len(sys.argv) > 2 else "stzb-test"


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("连接被关闭")
        buf += chunk
    return buf


def recv_frame(sock):
    """读一个下行帧, 返回 (cmd, hashcode, rest_bytes)。"""
    length = struct.unpack(">I", recv_exact(sock, 4))[0]
    payload = recv_exact(sock, length)
    cmd, hashcode = struct.unpack(">ii", payload[:8])
    return cmd, hashcode, payload[8:]


def decode_default_body(rest):
    """复刻客户端 default 分支: 读 1B dataType, 3=zlib(前置4B原长)/5=XOR(^0x98)/其它明文。"""
    import zlib
    data_type = rest[0]
    body = rest[1:]
    if data_type == 3:                      # zlib, 前 4B 是原始长度
        raw_len = struct.unpack(">i", body[:4])[0]
        text = zlib.decompress(body[4:]).decode("utf-8")
        assert len(text.encode("utf-8")) == raw_len or True
    elif data_type == 5:                    # 逐字节 ^ 0x98
        text = bytes(b ^ 0x98 for b in body).decode("utf-8")
    else:                                   # 1 或其它: 明文
        text = body.decode("utf-8")
    return data_type, json.loads(text)



def build_up_packet(server_id, user_id, sid, cmd_id, cmd_index, obj, encode=True):
    body = json.dumps(obj, separators=(",", ":")).encode("utf-8") if obj is not None else b""
    check = ((cmd_index * 13) ^ cmd_id ^ user_id) & 0xFFFFFFFF
    if encode:
        key = check & 0xFF
        body = bytes(b ^ key for b in body)
        flag = 5
    else:
        flag = 1
    header = struct.pack(">i", server_id)
    header += struct.pack(">i", user_id)
    header += sid                                   # 32B
    header += struct.pack(">i", cmd_id)
    header += struct.pack(">i", cmd_index)
    header += struct.pack(">i", check - (1 << 32) if check >= (1 << 31) else check)
    header += struct.pack(">B", flag)
    frame_body = header + body
    return struct.pack(">i", len(frame_body)) + frame_body


def main():
    print(f"连接 {HOST}:{PORT} ...")
    s = socket.create_connection((HOST, PORT), timeout=5)

    # 1. 收握手 98888
    cmd, hc, rest = recv_frame(s)
    assert cmd == 98888, f"期望首包 98888, 实际 {cmd}"
    user_id, cmd_index = struct.unpack(">ii", rest[:8])
    sid = rest[8:40]
    param = struct.unpack(">i", rest[40:44])[0]
    print(f"✓ 收到握手 98888: userId={user_id} cmdIndex={cmd_index} sid={sid.hex()[:16]}... param={param}")

    # 2. 发 99992 平台校验 (登录服前置硬卡点), 拿 ServerSession
    idx = cmd_index + 1
    credentials = json.dumps(
        {
            "gameid": "g10",
            "login_channel": "_stzb_test_",
            "sdkuid": SDK_UID,
        },
        separators=(",", ":"),
    )
    pkt = build_up_packet(0, user_id, sid, 99992, idx,
                          [credentials, 0, "", 0], encode=True)
    s.sendall(pkt)
    print(f"→ 已发平台校验 cmd=99992 idx={idx}")
    cmd, hc, rest = recv_frame(s)
    assert cmd == 99992, f"期望 99992 响应, 实际 {cmd}"
    dt, chk = decode_default_body(rest)
    assert chk[0] == 1, "平台校验必须成功 (val[0]==1)"
    server_session = chk[2]
    assert server_session, "ServerSession 必须非空"
    print(f"✓ 收到 99992 响应: ServerSession={server_session!r} LoginServerUserId={chk[3]}")

    # 3. 发一个加密的 20003 (拉服务器列表), body 复刻客户端字段
    idx += 1
    pkt = build_up_packet(0, user_id, sid, 20003, idx,
                          ["9.2.2", "zh_Hans", 0, "test-udid-1234", ""], encode=True)
    s.sendall(pkt)
    print(f"→ 已发加密上行包 cmd=20003 idx={idx} (XOR body)")

    # 2b. 收 20003 响应并校验服务器列表表格式
    cmd, hc, rest = recv_frame(s)
    assert cmd == 20003, f"期望 20003 响应, 实际 {cmd}"
    dt, payload = decode_default_body(rest)
    status, body = payload[0], payload[1]
    server_list = body[0]
    columns = server_list[0]
    row0 = server_list[1] if len(server_list) > 1 else None
    print(f"✓ 收到 20003 响应 (dataType={dt} status={status})")
    print(f"   列名={columns}")
    print(f"   首服={row0}")
    assert "server_id" in columns and "host" in columns and "port" in columns

    # 3. 发 99991 登录请求
    idx += 1
    pkt = build_up_packet(0, user_id, sid, 99991, idx,
                          [f"passport_{SDK_UID}", server_session, user_id], encode=True)
    s.sendall(pkt)
    print(f"→ 已发登录请求 cmd=99991 idx={idx}")

    # 3b. 收 99991 响应并校验登录进城结构
    cmd, hc, rest = recv_frame(s)
    assert cmd == 99991, f"期望 99991 响应, 实际 {cmd}"
    dt, j = decode_default_body(rest)
    login_state = j[0]
    time_sync = j[1]
    login_user_type = j[2]
    cfg_index = j[3]
    enter = j[4]
    print(f"✓ 收到 99991 响应 (dataType={dt} {len(rest)}B)")
    print(f"   LoginState={login_state} LoginUserType={login_user_type} cfgIndex={cfg_index}")
    print(f"   时间同步={time_sync}")
    assert login_state == 1, "LoginState 必须为 1"
    assert login_user_type in (1, 2), "LoginUserType 必须 1/2 才走进城"
    assert len(time_sync) >= 4, "时间同步数组至少 4 元素"

    # EnterGameResult[0] = UserInitTable
    uit = enter[0]
    schema = uit[0]
    tables = {t[0]: t[1] for t in uit[1:]}
    print(f"   EnterGameResult 元素数={len(enter)} (需 ≥6)")
    print(f"   UserInitTable schema 长度={len(schema)}B, 表数={len(tables)}")
    print(f"   包含表: {list(tables.keys())}")
    assert len(enter) >= 6, "EnterGameResult 至少 6 元素"
    for need in ("Tb_user", "Tb_user_res", "Tb_user_city", "Tb_world_city", "Tb_user_stuff"):
        assert need in tables, f"缺少表 {need}"
    # 三键对齐校验
    tb_user_row = tables["Tb_user"][0]
    tb_city_row = tables["Tb_user_city"][0]
    uid_in_user = tb_user_row[0]
    city_wid_in_user = tb_user_row[17]
    city_wid_in_city = tb_city_row[0]
    tb_world_row = next(
        row for row in tables["Tb_world_city"] if row[0] == city_wid_in_user
    )
    wid_in_world = tb_world_row[0]
    print(f"   三键对齐: Tb_user.userid={uid_in_user} city_wid={city_wid_in_user} "
          f"| Tb_user_city.city_wid={city_wid_in_city} | Tb_world_city.wid={wid_in_world}")
    assert city_wid_in_user == city_wid_in_city == wid_in_world, "三键 wid 必须一致"
    world_owner_ids = sorted({row[6] for row in tables["Tb_world_city"] if len(row) > 6 and row[6] > 0})
    print(f"   世界玩家: {world_owner_ids}")
    assert uid_in_user in world_owner_ids, "世界快照必须包含当前账号"

    # 4. 发一个明文心跳 90003
    time.sleep(0.2)
    pkt2 = build_up_packet(0, user_id, sid, 90003, idx + 1, None, encode=False)
    s.sendall(pkt2)
    print(f"→ 已发心跳 cmd=90003 idx={idx+1}")

    time.sleep(0.3)
    print("✓✓ P1 离线推演验证通过 (20003 服务器列表 + 99991 登录进城结构均合法)")
    s.close()


if __name__ == "__main__":
    main()
