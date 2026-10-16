package com.eltavine.the_abyssal_sovereign.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the Java side of the wire protocol against the daemon's encoder. The byte layouts here are
 * written by hand to mirror {@code native/src/main.zig} exactly; if either side drifts, these fail.
 */
class ProtocolCodecTest {

    @Test
    void snapshotEncodesEveryFieldLittleEndian() {
        Snapshot snapshot = new Snapshot(
                7L, 0xABCDL,
                10.0, 64.0, -20.0,
                90.0f, 0.0f,
                20.0f, 20.0f,
                10.0, 65.0, -20.0,
                6000L,
                Protocol.FLAG_IN_WORLD | Protocol.FLAG_ACTIVE);
        byte[] bytes = snapshot.encode();
        assertEquals(Snapshot.BYTES, bytes.length);

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(7L, buf.getLong());
        assertEquals(0xABCDL, buf.getLong());
        assertEquals(10.0, buf.getDouble());
        assertEquals(64.0, buf.getDouble());
        assertEquals(-20.0, buf.getDouble());
        assertEquals(90.0f, buf.getFloat());
        assertEquals(0.0f, buf.getFloat());
        assertEquals(20.0f, buf.getFloat());
        assertEquals(20.0f, buf.getFloat());
        assertEquals(10.0, buf.getDouble());
        assertEquals(65.0, buf.getDouble());
        assertEquals(-20.0, buf.getDouble());
        assertEquals(6000L, buf.getLong());
        assertEquals((byte) (Protocol.FLAG_IN_WORLD | Protocol.FLAG_ACTIVE), buf.get());
    }

    @Test
    void helloConfigEncodesVersionFirst() {
        byte[] bytes = DaemonConfig.defaults().encode();
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(Protocol.PROTOCOL_VERSION, buf.getInt());
    }

    @Test
    void helloEndsWithBoltsPerTickDefaultingToOneHundredASecond() {
        byte[] bytes = DaemonConfig.defaults().encode();
        assertEquals(DaemonConfig.BYTES, bytes.length);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(bytes.length - 4);
        int perTick = buf.getInt();
        assertEquals(5, perTick);
        assertEquals(100, perTick * 20);
    }

    @Test
    void decodesAGlobalPurgeAsTheDaemonEncodesIt() {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 2);
        buf.put((byte) Protocol.CMD_PURGE);
        buf.putDouble(1.0).putDouble(2.0).putDouble(3.0);
        buf.putFloat(0.0f);
        buf.put((byte) Protocol.PURGE_GLOBAL);
        // A trailing command proves the purge consumed exactly its own bytes.
        buf.put((byte) Protocol.CMD_ATMOSPHERE);
        buf.putFloat(1.0f).putFloat(0.5f).putFloat(1.0f);

        byte[] payload = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, payload, 0, buf.position());

        List<BossCommand> commands = BossCommand.decodeBatch(payload);
        assertEquals(2, commands.size());
        BossCommand.Purge purge = assertInstanceOf(BossCommand.Purge.class, commands.get(0));
        assertTrue(purge.global());
        assertEquals(3.0, purge.z());
        assertInstanceOf(BossCommand.Atmosphere.class, commands.get(1));
    }

    @Test
    void decodesABatchOfCommandsTheDaemonWouldSend() {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 3); // count

        // BOSS_STATE
        buf.put((byte) Protocol.CMD_BOSS_STATE);
        buf.put((byte) 1);
        buf.putDouble(1.0).putDouble(64.0).putDouble(2.0);
        buf.putFloat(90.0f).putFloat(0.0f).putFloat(100.0f);
        buf.put((byte) Protocol.ANIM_ROAR);
        buf.putLong(42L);
        buf.putFloat(1.0f);
        buf.put((byte) Protocol.PHASE_CATACLYSM);

        // DAMAGE_LOCAL
        buf.put((byte) Protocol.CMD_DAMAGE_LOCAL);
        buf.putFloat(12.0f).putFloat(0.5f).putFloat(0.2f).putFloat(-0.5f);
        buf.put((byte) Protocol.DMG_SONIC);

        // SOUND
        buf.put((byte) Protocol.CMD_SOUND);
        buf.putShort((short) Protocol.SND_ROAR);
        buf.putDouble(1.0).putDouble(2.0).putDouble(3.0);
        buf.putFloat(3.0f).putFloat(1.0f);

        byte[] payload = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, payload, 0, buf.position());

        List<BossCommand> commands = BossCommand.decodeBatch(payload);
        assertEquals(3, commands.size());

        BossCommand.BossState state = assertInstanceOf(BossCommand.BossState.class, commands.get(0));
        assertTrue(state.present());
        assertEquals(100.0f, state.scale());
        assertEquals(Protocol.ANIM_ROAR, state.anim());
        assertEquals(Protocol.PHASE_CATACLYSM, state.phase());

        BossCommand.Damage damage = assertInstanceOf(BossCommand.Damage.class, commands.get(1));
        assertEquals(12.0f, damage.amount());
        assertEquals(Protocol.DMG_SONIC, damage.source());

        BossCommand.Sound sound = assertInstanceOf(BossCommand.Sound.class, commands.get(2));
        assertEquals(Protocol.SND_ROAR, sound.id());
    }

    @Test
    void unknownCommandStopsDecodingButKeepsWhatCameBefore() {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 2);
        buf.put((byte) Protocol.CMD_ATMOSPHERE);
        buf.putFloat(1.0f).putFloat(0.5f).putFloat(1.0f);
        buf.put((byte) 0x7F); // unknown type

        byte[] payload = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, payload, 0, buf.position());

        List<BossCommand> commands = BossCommand.decodeBatch(payload);
        assertEquals(1, commands.size());
        assertInstanceOf(BossCommand.Atmosphere.class, commands.get(0));
        assertFalse(commands.isEmpty());
    }
}
