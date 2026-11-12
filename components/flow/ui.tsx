import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import { useMemo, type ReactNode } from "react";
import { Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";

import { FLOW_RADIUS, FLOW_SPACING, FLOW_TOUCH, FLOW_TYPE } from "@/constants/flow-theme";
import type { VoiceLinkPalette } from "@/lib/voice-link/voice-link-provider";

/**
 * Shared bottom sheet used by the Console connection details, pairing and
 * Activity recovery. Keeping one shell means one set of touch targets and one
 * close behaviour across the app.
 */
export function FlowSheet(props: {
  visible: boolean;
  title: string;
  subtitle?: string;
  onClose: () => void;
  palette: VoiceLinkPalette;
  children: ReactNode;
}) {
  const styles = useMemo(() => createStyles(props.palette), [props.palette]);
  return (
    <Modal visible={props.visible} transparent animationType={Platform.OS === "android" ? "fade" : "slide"} onRequestClose={props.onClose}>
      <View style={styles.backdrop}>
        <Pressable accessibilityRole="button" accessibilityLabel="Close" onPress={props.onClose} style={styles.backdropTouch} />
        <View style={styles.panel} accessibilityViewIsModal>
          <View style={styles.handle} />
          <View style={styles.header}>
            <View style={styles.headerCopy}>
              <Text style={styles.title}>{props.title}</Text>
              {props.subtitle ? <Text style={styles.subtitle}>{props.subtitle}</Text> : null}
            </View>
            <Pressable accessibilityRole="button" accessibilityLabel="Close" onPress={props.onClose} hitSlop={10} style={styles.close}>
              <MaterialIcons name="close" size={20} color={props.palette.textMuted} />
            </Pressable>
          </View>
          <ScrollView style={{ flexGrow: 0, flexShrink: 1 }} contentContainerStyle={styles.body} showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled">
            {props.children}
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}

export type ChipTone = "neutral" | "good" | "warn" | "bad";

export function StatusChip({ tone, label, palette, icon }: { tone: ChipTone; label: string; palette: VoiceLinkPalette; icon?: keyof typeof MaterialIcons.glyphMap }) {
  const styles = useMemo(() => createStyles(palette), [palette]);
  const filled = tone === "good" || tone === "bad";
  const color = filled ? "#FFFFFF" : tone === "warn" ? palette.warning : palette.textMuted;
  return (
    <View style={[styles.chip, styles[`chip_${tone}` as "chip_neutral"]]} accessibilityRole="text">
      {icon ? <MaterialIcons name={icon} size={14} color={color} /> : null}
      <Text style={[styles.chipText, { color }]} numberOfLines={1}>
        {label}
      </Text>
    </View>
  );
}

export function FlowRow({
  icon,
  title,
  detail,
  trailing,
  onPress,
  palette,
  destructive,
  iconColor,
}: {
  icon?: keyof typeof MaterialIcons.glyphMap;
  title: string;
  detail?: string;
  trailing?: ReactNode;
  onPress?: () => void;
  palette: VoiceLinkPalette;
  destructive?: boolean;
  /** Optional glyph tint (e.g. gold crown on Subscription). */
  iconColor?: string;
}) {
  const styles = useMemo(() => createStyles(palette), [palette]);
  const glyphColor = destructive ? palette.error : iconColor ?? palette.text;
  const content = (
    <>
      {icon ? (
        <View style={styles.rowIcon}>
          <MaterialIcons name={icon} size={18} color={glyphColor} />
        </View>
      ) : null}
      <View style={styles.rowCopy}>
        <Text style={[styles.rowTitle, destructive ? { color: palette.error } : null]}>{title}</Text>
        {detail ? <Text style={styles.rowDetail}>{detail}</Text> : null}
      </View>
      {trailing ?? (onPress ? <MaterialIcons name="chevron-right" size={20} color={palette.textMuted} /> : null)}
    </>
  );
  if (!onPress) return <View style={styles.row}>{content}</View>;
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={({ pressed }) => [styles.row, pressed && styles.rowPressed]}>
      {content}
    </Pressable>
  );
}

export function FlowButton({
  label,
  onPress,
  palette,
  variant = "primary",
  disabled,
  icon,
  loading,
  accessibilityLabel,
}: {
  label: string;
  onPress: () => void;
  palette: VoiceLinkPalette;
  variant?: "primary" | "secondary" | "danger" | "quiet";
  disabled?: boolean;
  icon?: keyof typeof MaterialIcons.glyphMap;
  loading?: boolean;
  accessibilityLabel?: string;
}) {
  const styles = useMemo(() => createStyles(palette), [palette]);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel ?? label}
      accessibilityState={{ disabled: Boolean(disabled), busy: Boolean(loading) }}
      onPress={disabled || loading ? undefined : onPress}
      style={({ pressed }) => [
        styles.button,
        styles[`button_${variant}` as "button_primary"],
        pressed && styles.buttonPressed,
        (disabled || loading) && styles.buttonDisabled,
      ]}
    >
      {icon ? <MaterialIcons name={loading ? "hourglass-empty" : icon} size={17} color={variant === "primary" ? palette.onCta : palette.text} /> : null}
      <Text style={[styles.buttonText, variant === "primary" && styles.buttonTextPrimary]}>{loading ? "Working…" : label}</Text>
    </Pressable>
  );
}

export function FlowNotice({ tone, title, detail, palette, action }: { tone: ChipTone; title: string; detail?: string; palette: VoiceLinkPalette; action?: ReactNode }) {
  const styles = useMemo(() => createStyles(palette), [palette]);
  return (
    <View style={[styles.notice, styles[`notice_${tone}` as "notice_neutral"]]} accessibilityLiveRegion="polite">
      <MaterialIcons
        name={tone === "good" ? "check-circle-outline" : tone === "bad" ? "error-outline" : "info-outline"}
        size={18}
        color={tone === "bad" ? palette.error : tone === "good" ? palette.success : palette.text}
      />
      <View style={{ flex: 1 }}>
        <Text style={styles.noticeTitle}>{title}</Text>
        {detail ? <Text style={styles.noticeDetail}>{detail}</Text> : null}
      </View>
      {action}
    </View>
  );
}

export function FlowLabel({ text, palette }: { text: string; palette: VoiceLinkPalette }) {
  const styles = useMemo(() => createStyles(palette), [palette]);
  return <Text style={styles.sectionLabel}>{text}</Text>;
}

function createStyles(palette: VoiceLinkPalette) {
  return StyleSheet.create({
    backdrop: { flex: 1, backgroundColor: "rgba(0,0,0,0.62)", justifyContent: "flex-end" },
    backdropTouch: { ...StyleSheet.absoluteFillObject },
    panel: {
      backgroundColor: palette.panel,
      borderTopLeftRadius: FLOW_RADIUS.card + 6,
      borderTopRightRadius: FLOW_RADIUS.card + 6,
      borderWidth: 1,
      borderColor: palette.border,
      borderBottomWidth: 0,
      paddingHorizontal: FLOW_SPACING.lg,
      paddingTop: FLOW_SPACING.sm,
      paddingBottom: FLOW_SPACING.xl + FLOW_SPACING.sm,
      maxHeight: "86%",
    },
    handle: { width: 42, height: 4, borderRadius: 4, backgroundColor: palette.border, alignSelf: "center", marginBottom: FLOW_SPACING.md },
    header: { flexDirection: "row", alignItems: "flex-start", gap: FLOW_SPACING.sm, flexShrink: 0, paddingBottom: FLOW_SPACING.sm },
    headerCopy: { flex: 1 },
    title: { color: palette.text, fontSize: 21, fontWeight: "700", letterSpacing: -0.3 },
    subtitle: { color: palette.textMuted, fontSize: FLOW_TYPE.secondary, lineHeight: 20, marginTop: 6 },
    body: { gap: FLOW_SPACING.sm, paddingTop: FLOW_SPACING.md },
    close: { width: FLOW_TOUCH.compact, height: FLOW_TOUCH.compact, borderRadius: FLOW_TOUCH.compact / 2, alignItems: "center", justifyContent: "center", backgroundColor: palette.panelSoft },
    chip: { flexDirection: "row", alignItems: "center", gap: 5, minHeight: 26, paddingHorizontal: 10, borderRadius: FLOW_RADIUS.pill, borderWidth: 0, alignSelf: "flex-start" },
    chip_neutral: { borderWidth: 1, borderColor: palette.border, backgroundColor: palette.panelSoft },
    chip_good: { backgroundColor: palette.success },
    chip_warn: { backgroundColor: `${palette.warning}33`, borderWidth: 1, borderColor: `${palette.warning}66` },
    chip_bad: { backgroundColor: palette.error },
    chipText: { fontSize: FLOW_TYPE.meta, fontWeight: "700" },
    row: { minHeight: FLOW_TOUCH.min, flexDirection: "row", alignItems: "center", gap: FLOW_SPACING.sm, paddingVertical: 10, borderRadius: FLOW_RADIUS.control },
    rowPressed: { opacity: 0.72 },
    rowIcon: { width: 34, height: 34, borderRadius: 17, backgroundColor: palette.iconSurface, alignItems: "center", justifyContent: "center" },
    rowCopy: { flex: 1, gap: 2 },
    rowTitle: { color: palette.text, fontSize: FLOW_TYPE.secondary, fontWeight: "700" },
    rowDetail: { color: palette.textMuted, fontSize: FLOW_TYPE.meta, lineHeight: 18 },
    button: {
      minHeight: FLOW_TOUCH.min,
      paddingHorizontal: FLOW_SPACING.md,
      borderRadius: FLOW_RADIUS.pill,
      borderWidth: 1,
      borderColor: palette.border,
      flexDirection: "row",
      alignItems: "center",
      justifyContent: "center",
      gap: 7,
    },
    button_primary: { backgroundColor: palette.cta, borderColor: palette.cta },
    button_secondary: { backgroundColor: palette.panelSoft, borderColor: palette.border },
    button_danger: { backgroundColor: `${palette.error}14`, borderColor: `${palette.error}55` },
    button_quiet: { backgroundColor: "transparent", borderColor: "transparent" },
    buttonPressed: { opacity: 0.85 },
    buttonDisabled: { opacity: 0.45 },
    buttonText: { color: palette.text, fontSize: FLOW_TYPE.secondary, fontWeight: "700" },
    buttonTextPrimary: { color: palette.onCta },
    notice: { flexDirection: "row", alignItems: "flex-start", gap: FLOW_SPACING.xs, padding: FLOW_SPACING.sm, borderRadius: FLOW_RADIUS.control, borderWidth: 1 },
    notice_neutral: { borderColor: `${palette.border}`, backgroundColor: palette.panelSoft },
    notice_good: { borderColor: `${palette.success}44`, backgroundColor: `${palette.success}10` },
    notice_warn: { borderColor: `${palette.textMuted}44`, backgroundColor: `${palette.panelSoft}` },
    notice_bad: { borderColor: `${palette.error}44`, backgroundColor: `${palette.error}10` },
    noticeTitle: { color: palette.text, fontSize: FLOW_TYPE.secondary, fontWeight: "700", lineHeight: 20 },
    noticeDetail: { color: palette.textMuted, fontSize: FLOW_TYPE.meta, lineHeight: 18, marginTop: 3 },
    sectionLabel: { color: palette.textMuted, fontSize: FLOW_TYPE.label, fontWeight: "700", letterSpacing: 0.2, marginTop: FLOW_SPACING.xs },
  });
}
