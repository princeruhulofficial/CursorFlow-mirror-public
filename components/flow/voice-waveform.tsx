import { useEffect, useRef, useState } from "react";
import { View } from "react-native";

import { WAVE_BARS, barHeight, nextWaveBars } from "@/lib/voice-link/waveform";

/** Flowing bars that follow the voice level while Home is listening. */
export function VoiceWaveform(props: { getLevel?: () => number; color: string; reducedMotion: boolean; height: number; width: number }) {
  const [bars, setBars] = useState<number[]>(() => nextWaveBars([], 0, 0));
  const tickRef = useRef(0);
  const getLevelRef = useRef(props.getLevel);
  getLevelRef.current = props.getLevel;
  useEffect(() => {
    const timer = setInterval(() => {
      tickRef.current += 1;
      let level = 0;
      try { level = getLevelRef.current?.() ?? 0; } catch { level = 0; }
      setBars((prev) => nextWaveBars(prev, level, tickRef.current));
    }, props.reducedMotion ? 250 : 70);
    return () => clearInterval(timer);
  }, [props.reducedMotion]);
  const gap = 3;
  const barWidth = Math.max(2, Math.floor((props.width - gap * (WAVE_BARS - 1)) / WAVE_BARS));
  return (
    <View pointerEvents="none" style={{ width: props.width, height: props.height, flexDirection: "row", alignItems: "center", justifyContent: "center", gap }}>
      {bars.map((value, index) => (
        <View key={index} style={{ width: barWidth, height: barHeight(value, index, WAVE_BARS, props.height), borderRadius: barWidth, backgroundColor: props.color, opacity: 0.55 + 0.45 * value }} />
      ))}
    </View>
  );
}
