export interface SensorDetail {
  sensorType: string;
  roadName: string;
  locationDesc: string;
  authority: string;
  epaCode: string;
  target: string;
  targetDetail: String;
  height: number;
  distance: Array<number>;
}

export interface Monitor {
  _id: string;
  desc: string;
  monitorTypes: Array<string>;
  tags: Array<string>;
  location: Array<number> | undefined;
  shortCode: string | undefined;
  code: string | undefined;
  enabled: boolean | undefined;
  county: string | undefined;
  district: string | undefined;
  sensorDetail: SensorDetail | undefined;
}

export interface MonitorState {
  monitors: Array<Monitor>;
}
