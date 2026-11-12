import { Tabs } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";

import { HapticTab } from "@/components/haptic-tab";
import { Platform, StyleSheet, View } from "react-native";
import { useVoiceLink } from "@/lib/voice-link/voice-link-provider";

function ThinIcon({
  name,
  color,
  focused,
}: {
  name: keyof typeof MaterialIcons.glyphMap;
  color: string;
  focused: boolean;
}) {
  const { palette } = useVoiceLink();
  // The selected highlight sits behind the icon only, inside its own tab, so it can
  // never spill over a neighbouring tab label.
  return (
    <View style={{ width: 52, height: 30, borderRadius: 15, alignItems: "center", justifyContent: "center", backgroundColor: focused ? palette.inset : "transparent" }}>
      <MaterialIcons name={name} size={22} color={color} />
    </View>
  );
}

export default function TabLayout() {
  const { palette } = useVoiceLink();
  const insets = useSafeAreaInsets();
  const bottomPadding = Platform.OS === "web" ? 12 : Math.max(insets.bottom, 8);
  const tabBarHeight = 74;
  const active = palette.text;
  const inactive = palette.textMuted;

  return (
    <Tabs
      screenOptions={{
        tabBarActiveTintColor: active,
        tabBarInactiveTintColor: inactive,
        headerShown: false,
        tabBarButton: HapticTab,
        tabBarLabelStyle: { fontSize: 12, fontWeight: "500", marginTop: 3 },
        tabBarAllowFontScaling: false,
        tabBarItemStyle: { flex: 1, minWidth: 0, borderRadius: 30, marginHorizontal: 2, overflow: "hidden" },
        tabBarHideOnKeyboard: true,
        tabBarStyle: {
          marginHorizontal: 16,
          marginBottom: bottomPadding,
          paddingHorizontal: 5,
          paddingTop: 7,
          paddingBottom: 7,
          borderRadius: 40,
          height: tabBarHeight,
          backgroundColor: palette.panel,
          borderColor: palette.border,
          borderWidth: StyleSheet.hairlineWidth,
          borderTopWidth: 0,
          shadowColor: "#000000",
          shadowOpacity: 0.09,
          shadowRadius: 18,
          shadowOffset: { width: 0, height: 8 },
          elevation: 8,
        },
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: "Home",
          tabBarIcon: ({ color, focused }) => <ThinIcon name="home" color={color} focused={focused} />,
        }}
      />
      <Tabs.Screen
        name="activity"
        options={{
          title: "History",
          tabBarIcon: ({ color, focused }) => <ThinIcon name="history" color={color} focused={focused} />,
        }}
      />
      <Tabs.Screen
        name="dictionary"
        options={{
          title: "Dictionary",
          tabBarIcon: ({ color, focused }) => <ThinIcon name="menu-book" color={color} focused={focused} />,
        }}
      />
      <Tabs.Screen
        name="account"
        options={{
          title: "Account",
          tabBarIcon: ({ color, focused }) => (
            <ThinIcon name="person-outline" color={color} focused={focused} />
          ),
        }}
      />
      <Tabs.Screen name="controls" options={{ href: null }} />
      <Tabs.Screen name="console" options={{ href: null }} />
    </Tabs>
  );
}
