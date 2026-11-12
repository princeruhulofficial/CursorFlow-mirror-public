import { useCallback, useMemo, useRef, useState } from "react";
import {
  Animated,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";
import { useFocusEffect, useRouter } from "expo-router";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";

import { ScreenContainer } from "@/components/screen-container";
import { FLOW_RADIUS, FLOW_SPACING, FLOW_TYPE } from "@/constants/flow-theme";
import { formatMinutesSaved, loadAllUsageStats, type UsageStats } from "@/lib/voice-link/usage-stats";
import { useVoiceLink } from "@/lib/voice-link/voice-link-provider";

type ModeId = "dictate" | "translate" | "write";

const MODES: {
  id: ModeId;
  title: string;
  body: string;
  exampleBefore: string;
  exampleAfter: string;
  icon: keyof typeof MaterialIcons.glyphMap;
}[] = [
  {
    id: "dictate",
    title: "Dictate",
    body: "Speak naturally. Flow AI cleans filler and punctuation.",
    exampleBefore: "um so yeah can you send that file like tomorrow morning please",
    exampleAfter: "Can you send that file tomorrow morning, please?",
    icon: "mic",
  },
  {
    id: "translate",
    title: "Translate",
    body: "Hold the mic, swipe up, and speak. Text lands in your target language.",
    exampleBefore: "আমি কাল মিটিংয়ে আসব",
    exampleAfter: "I will attend the meeting tomorrow.",
    icon: "translate",
  },
  {
    id: "write",
    title: "Write & edit",
    body: "Describe the message you want. Flow AI drafts or rewrites it.",
    exampleBefore: "make this more polite and shorter",
    exampleAfter: "Could you please review this when you have a moment?",
    icon: "edit",
  },
];

const TILES: { id: string; title: string; body: string; href: string; icon: keyof typeof MaterialIcons.glyphMap }[] = [
  { id: "clipboard", title: "Clipboard", body: "Send copied text to the PC, or bring it back.", href: "/(tabs)/console?open=clipboard", icon: "content-paste" },
  { id: "scan", title: "Scan", body: "Scan pages into one PDF on your PC.", href: "/(tabs)/console?open=scan", icon: "document-scanner" },
  { id: "attach", title: "Attach", body: "Send photos and files to the PC in one tap.", href: "/(tabs)/console?open=attach", icon: "attach-file" },
  { id: "panel", title: "Quick panel", body: "A small bubble at the screen edge, so you rarely open the app.", href: "/(tabs)/console?open=panel", icon: "bubble-chart" },
];

export default function HomeScreen() {
  const { palette, device, relayConnection, relaySuspended } = useVoiceLink();
  const relay = relaySuspended ? null : relayConnection;
  const router = useRouter();
  const [stats, setStats] = useState<UsageStats>({ wordsDictated: 0, dictationSessions: 0 });
  const [mode, setMode] = useState<ModeId | null>(null);
  const fade = useRef(new Animated.Value(0)).current;

  useFocusEffect(
    useCallback(() => {
      void loadAllUsageStats().then(setStats);
    }, []),
  );

  const openMode = (id: ModeId) => {
    setMode(id);
    fade.setValue(0);
    Animated.timing(fade, { toValue: 1, duration: 320, useNativeDriver: true }).start();
  };

  const active = useMemo(() => MODES.find((m) => m.id === mode) ?? null, [mode]);
  const saved = formatMinutesSaved(stats.wordsDictated);

  return (
    <ScreenContainer>
      <ScrollView contentContainerStyle={styles.wrap} showsVerticalScrollIndicator={false}>
        <Text style={[styles.kicker, { color: palette.textMuted }]}>Cursor Flow</Text>
        <Text style={[styles.title, { color: palette.text }]}>Your phone, working for your PC.</Text>
        <Text style={[styles.lead, { color: palette.textMuted }]}>
          Speak or type on your phone and it lands at your PC cursor. Move files and clipboard, scan pages, and let Flow AI write.
        </Text>

        {device ? (
          <View style={[styles.pcCard, { backgroundColor: palette.panel, borderColor: palette.border }]}>
            <View style={styles.pcRow}>
              <View style={[styles.dot, { backgroundColor: relay ? palette.success : palette.textMuted }]} />
              <Text style={[styles.pcName, { color: palette.text }]} numberOfLines={1}>{device.name}</Text>
              <Text style={[styles.pcState, { color: relay ? palette.success : palette.textMuted }]}>{relay ? "Connected" : "Paused"}</Text>
            </View>
          </View>
        ) : (
          <Pressable
            accessibilityRole="button"
            onPress={() => router.push("/(tabs)/console?openPairing=1")}
            style={({ pressed }) => [styles.pcBtn, { backgroundColor: palette.cta }, pressed && { opacity: 0.85 }]}
          >
            <MaterialIcons name="qr-code-scanner" size={22} color={palette.onCta} />
            <Text style={[styles.pcLabel, { color: palette.onCta }]}>Connect your PC</Text>
          </Pressable>
        )}

        <Pressable
          accessibilityRole="button"
          onPress={() => router.push("/(tabs)/console")}
          style={({ pressed }) => [styles.hero, { backgroundColor: palette.panel, borderColor: palette.border }, pressed && { opacity: 0.9 }]}
        >
          <View style={[styles.heroIcon, { backgroundColor: palette.cta }]}>
            <MaterialIcons name="keyboard-voice" size={26} color={palette.onCta} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={[styles.heroTitle, { color: palette.text }]}>Live typing to your PC</Text>
            <Text style={[styles.heroBody, { color: palette.textMuted }]}>Words appear at the PC cursor as you speak or type. Enter is one tap away.</Text>
          </View>
          <MaterialIcons name="chevron-right" size={24} color={palette.textMuted} />
        </Pressable>

        <View style={styles.statsRow}>
          <View style={[styles.statCard, { backgroundColor: palette.panel, borderColor: palette.border }]}>
            <Text style={[styles.statValue, { color: palette.text }]}>{stats.wordsDictated.toLocaleString()}</Text>
            <Text style={[styles.statLabel, { color: palette.textMuted }]}>Words dictated</Text>
          </View>
          <View style={[styles.statCard, { backgroundColor: palette.panel, borderColor: palette.border }]}>
            <Text style={[styles.statValue, { color: palette.text }]}>{saved}</Text>
            <Text style={[styles.statLabel, { color: palette.textMuted }]}>Time saved</Text>
          </View>
        </View>
        <Text style={[styles.statHint, { color: palette.textMuted }]}>Time saved assumes 40 words per minute of typing.</Text>

        <View style={styles.grid}>
          {TILES.map((t) => (
            <Pressable
              key={t.id}
              accessibilityRole="button"
              accessibilityLabel={`${t.title}. ${t.body}`}
              onPress={() => router.push(t.href as never)}
              style={({ pressed }) => [styles.tile, { backgroundColor: palette.panel, borderColor: palette.border }, pressed && { opacity: 0.88 }]}
            >
              <View style={[styles.tileIcon, { backgroundColor: palette.panelSoft }]}>
                <MaterialIcons name={t.icon} size={22} color={palette.accentBlue} />
              </View>
              <Text style={[styles.tileTitle, { color: palette.text }]}>{t.title}</Text>
              <Text style={[styles.tileBody, { color: palette.textMuted }]} numberOfLines={2}>{t.body}</Text>
            </Pressable>
          ))}
        </View>

        <Text style={[styles.section, { color: palette.text }]}>Flow AI</Text>
        {MODES.map((item) => (
          <Pressable
            key={item.id}
            accessibilityRole="button"
            onPress={() => openMode(item.id)}
            style={[styles.modeCard, { backgroundColor: palette.panel, borderColor: palette.border }]}
          >
            <View style={[styles.modeIcon, { backgroundColor: palette.panelSoft }]}>
              <MaterialIcons name={item.icon} size={22} color={palette.text} />
            </View>
            <View style={{ flex: 1 }}>
              <Text style={[styles.modeTitle, { color: palette.text }]}>{item.title}</Text>
              <Text style={[styles.modeBody, { color: palette.textMuted }]}>{item.body}</Text>
            </View>
            <MaterialIcons name="chevron-right" size={22} color={palette.textMuted} />
          </Pressable>
        ))}
      </ScrollView>

      <Modal visible={!!active} transparent animationType="fade" onRequestClose={() => setMode(null)}>
        <Pressable style={styles.modalBackdrop} onPress={() => setMode(null)}>
          <Animated.View
            style={[
              styles.modalCard,
              {
                backgroundColor: palette.panel,
                opacity: fade,
                transform: [{ translateY: fade.interpolate({ inputRange: [0, 1], outputRange: [24, 0] }) }],
              },
            ]}
          >
            {active ? (
              <>
                <Text style={[styles.modalTitle, { color: palette.text }]}>{active.title}</Text>
                <Text style={[styles.modalBody, { color: palette.textMuted }]}>{active.body}</Text>
                <View style={[styles.exampleBox, { backgroundColor: palette.inset }]}>
                  <Text style={[styles.exampleLabel, { color: palette.textMuted }]}>You say</Text>
                  <Text style={[styles.exampleText, { color: palette.textMuted }]}>{active.exampleBefore}</Text>
                </View>
                <View style={[styles.exampleBox, { backgroundColor: palette.panelSoft, borderWidth: StyleSheet.hairlineWidth, borderColor: palette.border }]}>
                  <Text style={[styles.exampleLabel, { color: palette.textMuted }]}>Flow AI</Text>
                  <Text style={[styles.exampleText, { color: palette.text }]}>{active.exampleAfter}</Text>
                </View>
                <Pressable style={[styles.modalClose, { backgroundColor: palette.cta }]} onPress={() => setMode(null)}>
                  <Text style={[styles.modalCloseLabel, { color: palette.onCta }]}>Got it</Text>
                </Pressable>
              </>
            ) : null}
          </Animated.View>
        </Pressable>
      </Modal>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  wrap: { paddingHorizontal: FLOW_SPACING.md, paddingTop: FLOW_SPACING.lg, paddingBottom: 48 },
  kicker: { fontSize: 13, fontWeight: "600", letterSpacing: 0.2 },
  title: { fontSize: 32, fontWeight: "700", letterSpacing: -0.8, marginTop: 4, marginBottom: 8 },
  lead: { fontSize: 15, lineHeight: 22, marginBottom: FLOW_SPACING.md },
  pcCard: { borderRadius: FLOW_RADIUS.card, borderWidth: StyleSheet.hairlineWidth, paddingHorizontal: 16, paddingVertical: 12, gap: 10 },
  pcRow: { flexDirection: "row", alignItems: "center", gap: 10, minHeight: 28 },
  dot: { width: 10, height: 10, borderRadius: 5 },
  pcName: { flex: 1, fontSize: 16, fontWeight: "600" },
  pcState: { fontSize: 14, fontWeight: "600" },
  hero: { flexDirection: "row", alignItems: "center", gap: 14, borderRadius: FLOW_RADIUS.card, borderWidth: StyleSheet.hairlineWidth, padding: 16, marginTop: 14 },
  heroIcon: { width: 52, height: 52, borderRadius: 26, alignItems: "center", justifyContent: "center" },
  heroTitle: { fontSize: 18, fontWeight: "700", letterSpacing: -0.2 },
  heroBody: { fontSize: 13, lineHeight: 18, marginTop: 3 },
  grid: { flexDirection: "row", flexWrap: "wrap", justifyContent: "space-between", rowGap: 12, marginTop: 12, marginBottom: FLOW_SPACING.lg },
  tile: { width: "48.3%", minHeight: 128, borderRadius: FLOW_RADIUS.card, borderWidth: StyleSheet.hairlineWidth, padding: 14 },
  tileIcon: { width: 40, height: 40, borderRadius: 20, alignItems: "center", justifyContent: "center", marginBottom: 10 },
  tileTitle: { fontSize: 15, fontWeight: "700" },
  tileBody: { fontSize: 12, lineHeight: 16, marginTop: 3 },
  statsRow: { flexDirection: "row", gap: 12, marginTop: 6 },
  statCard: { flex: 1, borderRadius: FLOW_RADIUS.card, borderWidth: StyleSheet.hairlineWidth, paddingVertical: 18, paddingHorizontal: 14 },
  statValue: { fontSize: 28, fontWeight: "700", letterSpacing: -0.5 },
  statLabel: { fontSize: 13, marginTop: 4 },
  statHint: { fontSize: 12, marginTop: 8, marginBottom: FLOW_SPACING.md },
  pcBtn: { backgroundColor: "#111", borderRadius: FLOW_RADIUS.pill, minHeight: 56, paddingHorizontal: 22, flexDirection: "row", alignItems: "center", justifyContent: "center", gap: 10 },
  pcLabel: { color: "#fff", fontSize: 17, fontWeight: "700" },
  section: { fontSize: 17, fontWeight: "700", marginBottom: 10 },
  modeCard: { borderRadius: FLOW_RADIUS.card, borderWidth: StyleSheet.hairlineWidth, padding: 16, marginBottom: 12, flexDirection: "row", alignItems: "center", gap: 12 },
  modeIcon: { width: 44, height: 44, borderRadius: 22, backgroundColor: "#F0F1F3", alignItems: "center", justifyContent: "center" },
  modeTitle: { fontSize: 17, fontWeight: "700" },
  modeBody: { fontSize: 13, lineHeight: 18, marginTop: 2 },
  modalBackdrop: { flex: 1, backgroundColor: "rgba(0,0,0,0.35)", justifyContent: "flex-end", padding: 16 },
  modalCard: { borderRadius: 28, padding: 22, gap: 10 },
  modalTitle: { fontSize: 22, fontWeight: "700" },
  modalBody: { fontSize: 15, lineHeight: 21, marginBottom: 6 },
  exampleBox: { backgroundColor: "#F4F5F7", borderRadius: 16, padding: 14 },
  exampleLabel: { fontSize: 11, fontWeight: "700", color: "#8E8E93", marginBottom: 4, letterSpacing: 0.3 },
  exampleText: { fontSize: 15, lineHeight: 21 },
  modalClose: { marginTop: 8, backgroundColor: "#111", borderRadius: FLOW_RADIUS.pill, paddingVertical: 14, alignItems: "center" },
  modalCloseLabel: { color: "#fff", fontWeight: "700", fontSize: 15 },
});
