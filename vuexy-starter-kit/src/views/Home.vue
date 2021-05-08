<template>
  <b-row class="match-height">
    <b-col lg="12" md="12">
      <b-card ref="loadingContainer" title="過去24小時統計">
        <b-row>
          <b-col><div id="chart_container1" /></b-col>
          <b-col><div id="chart_container2" /></b-col>
          <b-col><div id="chart_container3" /></b-col>
        </b-row>
        <b-row>
          <b-col>
            <b-table-simple bordered responsive outlined>
              <b-thead>
                <b-tr
                  ><b-td class="text-center" colspan="4"
                    >系統接受狀況</b-td
                  ></b-tr
                >
                <b-tr>
                  <b-th>群組</b-th>
                  <b-th>接收總數</b-th>
                  <b-th>斷線</b-th>
                  <b-th>完整率&lt;95%</b-th>
                </b-tr>
              </b-thead>
              <b-tbody>
                <b-tr v-for="group in sensorGroupSummary" :key="group.name">
                  <b-td>{{ group.name }}</b-td>
                  <b-td>{{ group.count }} </b-td>
                  <b-td>{{ group.disconnected }}</b-td>
                  <b-td>{{ group.expected }}</b-td>
                </b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
          <b-col>
            <b-table-simple bordered responsive outlined>
              <b-thead>
                <b-tr
                  ><b-td class="text-center" colspan="5"
                    >通訊中斷資訊</b-td
                  ></b-tr
                >
                <b-tr>
                  <b-th>型號</b-th>
                  <b-th>基隆</b-th>
                  <b-th>屏東</b-th>
                  <b-th>宜蘭</b-th>
                  <b-th>其他</b-th>
                </b-tr>
              </b-thead>
              <b-tbody>
                <b-tr v-for="group in sensorGroupSummary" :key="group.name">
                  <b-td>{{ group.name }}</b-td>
                  <b-td></b-td>
                  <b-td></b-td>
                  <b-td></b-td>
                  <b-td></b-td>
                </b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
        </b-row>
      </b-card>
    </b-col>
    <b-col lg="12" md="12">
      <b-card title="監測地圖🚀">
        <div class="map_container">
          <div id="sensorFilter" class="sensorFilter mt-2">
            <b-table-simple small>
              <b-tr>
                <b-th>縣市</b-th>
                <b-th>濃度</b-th>
                <b-th>區域劃分</b-th>
                <b-th>類型</b-th>
                <b-th>圖層選擇</b-th>
              </b-tr>
              <b-tbody>
                <b-tr>
                  <b-td
                    ><v-select
                      v-model="sensorStatusParam.county"
                      label="txt"
                      :reduce="entry => entry.value"
                      :options="countyFilters"
                  /></b-td>
                  <b-td
                    ><v-select
                      v-model="sensorStatusParam.pm25Threshold"
                      label="txt"
                      :reduce="entry => entry.value"
                      :options="pm25Filters"
                  /></b-td>
                  <b-td
                    ><v-select
                      v-model="sensorStatusParam.district"
                      label="txt"
                      :reduce="entry => entry.value"
                      :options="districtFilters"
                  /></b-td>
                  <b-td
                    ><v-select
                      v-model="sensorStatusParam.sensorType"
                      label="txt"
                      :reduce="entry => entry.value"
                      :options="sensorTypes"
                  /></b-td>
                  <b-td>
                    <v-select
                      v-model="mapLayer"
                      label="txt"
                      multiple
                      :reduce="entry => entry.value"
                      :options="mapLayerTypes"
                    />
                  </b-td>
                </b-tr>
              </b-tbody>
            </b-table-simple>
          </div>
          <GmapMap
            ref="mapRef"
            :center="mapCenter"
            :zoom="12"
            map-type-id="roadmap"
            class="map_canvas"
            :options="{
              zoomControl: true,
              mapTypeControl: false,
              scaleControl: false,
              streetViewControl: false,
              rotateControl: false,
              fullscreenControl: true,
              disableDefaultUi: false,
            }"
          >
            <GmapMarker
              v-for="(m, index) in sensorMarkers"
              :key="m._id"
              :position="m.position"
              :clickable="true"
              :title="m.title"
              :icon="m.iconUrl"
              @click="toggleInfoWindow(m, index)"
            />
            <GmapMarker
              v-for="(m, index) in epaMarkers"
              :key="m._id"
              :position="m.position"
              :clickable="true"
              :title="m.title"
              :icon="m.iconUrl"
              @click="toggleInfoWindow(m, index)"
            />
            <gmap-info-window
              :options="infoOptions"
              :position="infoWindowPos"
              :opened="infoWinOpen"
              @closeclick="infoWinOpen = false"
            />
          </GmapMap>
        </div>
        <div id="legend" class="legend shadow border border-dark m-2">
          <b-img src="../assets/images/legend.png" fluid />
        </div>
      </b-card>
    </b-col>
  </b-row>
</template>
<style scoped>
.sensorFilter {
  background-color: white;
}

.legend {
  /* min-width: 100px;*/
  background-color: white;
}

.airgreen div:before {
  background: #009865;
  background-color: rgb(0, 152, 101);
}

.airgreen {
  background-color: rgb(229, 244, 239);
}
</style>
<script>
import { mapActions, mapState, mapGetters } from 'vuex';
import axios from 'axios';
export default {
  data() {
    return {
      mapLayer: ['sensor', 'EPA'],
      sensorStatus: [],
      epaStatus: [],
      sensorStatusParam: {
        pm25Threshold: '',
        county: '',
        district: '',
        sensorType: '',
      },
      loader: undefined,
      refreshTimer: 0,
      infoWindowPos: null,
      infoWinOpen: false,
      currentMidx: null,

      infoOptions: {
        content: '',
        //optional: offset infowindow so it visually sits nicely on top of our marker
        pixelOffset: {
          width: 0,
          height: -35,
        },
      },
      sensorGroupSummary: [],
      pm25Filters: [
        {
          txt: '不限',
          value: '',
        },
        {
          txt: 'pm25 < 1',
          value: -1,
        },
        {
          txt: 'pm25 > 25',
          value: 25,
        },
        {
          txt: 'pm25 > 50',
          value: 50,
        },
      ],
      countyFilters: [
        {
          txt: '不限',
          value: '',
        },
        {
          txt: '基隆',
          value: '基隆市',
        },
        {
          txt: '屏東',
          value: '屏東',
        },
        {
          txt: '宜蘭',
          value: '宜蘭',
        },
      ],
      districtFilters: [
        {
          txt: '不限',
          value: '',
        },
        {
          txt: '基隆',
          value: '基隆市',
        },
        {
          txt: '屏東',
          value: '屏東縣',
        },
        {
          txt: '宜蘭',
          value: '宜蘭縣',
        },
      ],
      sensorTypes: [
        {
          txt: '不限',
          value: '',
        },
        {
          txt: '工業區',
          value: 'ID',
        },
        {
          txt: '其他汙染',
          value: 'OT',
        },
        {
          txt: '社區',
          value: 'CO',
        },
        {
          txt: '交通',
          value: 'TR',
        },
        {
          txt: '監測比對',
          value: 'MO',
        },
        {
          txt: '長期比對',
          value: 'LO',
        },
        {
          txt: '巡檢機',
          value: 'AO',
        },
      ],
      mapLayerTypes: [
        {
          txt: '感測器',
          value: 'sensor',
        },
        {
          txt: '環保署',
          value: 'EPA',
        },
        {
          txt: '通訊中斷',
          value: 'disconnect',
        },
        {
          txt: '收集率<95%',
          value: 'lt95',
        },
        {
          txt: '定值',
          value: 'constant',
        },
      ],
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapState('user', ['userInfo']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    mapCenter() {
      let county = this.sensorStatusParam.county;
      switch (county) {
        case '基隆市':
          return { lat: 25.127594828422044, lng: 121.7399713796935 };
        case '宜蘭':
          return { lat: 24.699449555878495, lng: 121.73719578861895 };
        case '屏東':
          return { lat: 22.55311029065028, lng: 120.55724117206266 };
      }

      return { lat: 25.127594828422044, lng: 121.7399713796935 };
    },
    sensorMarkers() {
      return this.markers(this.sensorStatus);
    },
    epaMarkers() {
      return this.markers(this.epaStatus);
    },
  },
  watch: {
    'sensorStatusParam.pm25Threshold': function () {
      this.getSensorStatus();
    },
    'sensorStatusParam.county': function () {
      if (this.sensorStatusParam.county === null)
        this.sensorStatusParam.county = '';
      this.getSensorStatus();
    },
    'sensorStatusParam.district': function () {
      this.getSensorStatus();
    },
    mapLayer(newMap, oldMap) {
      this.handlMapLayerChange(newMap, oldMap);
    },
  },
  async mounted() {
    const sensorFilter = document.getElementById('sensorFilter');
    this.$refs.mapRef.$mapPromise.then(map => {
      map.controls[google.maps.ControlPosition.TOP_CENTER].push(sensorFilter);
    });

    /*
    this.loader = this.$loading.show({
      // Optional parameters
      container: null,
      canCancel: false,
    }); */

    await this.fetchMonitors();
    this.refresh();
    this.refreshTimer = setInterval(() => {
      this.refresh();
    }, 60000);
    this.handlMapLayerChange(this.mapLayer, []);
  },
  beforeDestroy() {
    clearInterval(this.refreshTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    toggleInfoWindow(marker, idx) {
      this.infoWindowPos = marker.position;
      this.infoOptions.content = marker.infoText;

      //check if its the same marker that was selected if yes toggle
      if (this.currentMidx == idx) {
        this.infoWinOpen = !this.infoWinOpen;
      }

      //if different marker set infowindow to open and reset current marker index
      else {
        this.infoWinOpen = true;
        this.currentMidx = idx;
      }
    },
    getPM25Class(v) {
      if (v < 12) return { FPMI1: true };
      else if (v < 24) return { FPMI2: true };
      else if (v < 36) return { FPMI3: true };
      else if (v < 42) return { FPMI4: true };
      else if (v < 48) return { FPMI5: true };
      else if (v < 54) return { FPMI6: true };
      else if (v < 59) return { FPMI7: true };
      else if (v < 65) return { FPMI8: true };
      else if (v < 71) return { FPMI9: true };
      else return { FPMI10: true };
    },
    async refresh() {
      this.getTodaySummary();
    },
    async redrawMap() {
      for (const map of this.mapLayer) {
        switch (map) {
          case 'sensor':
            this.getSensorStatus();
            break;
          case 'EPA':
            break;
        }
      }
    },
    async getSensorStatus() {
      const ret = await axios.get('/RealtimeSensor', {
        params: this.sensorStatusParam,
      });
      this.sensorStatus = ret.data;
    },
    async getEpaStatus() {
      const ret = await axios.get('/RealtimeEPA');
      this.epaStatus = ret.data;
    },
    async getTodaySummary() {
      const res = await axios.get('/SensorSummary');
      const ret = res.data;
      this.sensorGroupSummary = ret;
    },
    handlMapLayerChange(newMap, oldMap) {
      const mapToClear = oldMap.filter(map => newMap.indexOf(map) === -1);
      mapToClear.forEach(map => {
        switch (map) {
          case 'sensor':
            this.sensorStatus.splice(0, this.sensorStatus.length);
            break;
          case 'EPA':
            this.epaStatus.splice(0, this.epaStatus.length);
            break;
        }
      });
      const mapToGet = newMap.filter(map => oldMap.indexOf(map) === -1);
      mapToGet.forEach(map => {
        switch (map) {
          case 'sensor':
            this.getSensorStatus();
            break;
          case 'EPA':
            this.getEpaStatus();
            break;
        }
      });
    },
    markers(statusArray) {
      const ret = [];
      const epaUrl = (name, v) => {
        let url = `https://chart.googleapis.com/chart?chst=d_fnote_title&chld=pinned_c|2|004400|l|${name}|PM2.5=${v}`;

        return url;
      };

      const getIconUrl = v => {
        let url = `https://chart.googleapis.com/chart?chst=d_bubble_text_small_withshadow&&chld=bb|`;

        if (v < 15.4) url += `${v}|009865|000000`;
        else if (v < 35.4) url += `${v}|FFFB26|000000`;
        else if (v < 54.4) url += `${v}|FF9835|000000`;
        else if (v < 150.4) url += `${v}|CA0034|000000`;
        else if (v < 250.4) url += `${v}|670099|000000`;
        else if (v < 350.4) url += `${v}|7E0123|000000`;
        else url += `${v}|7E0123|FFFFFF`;

        return url;
      };

      for (const stat of statusArray) {
        if (!stat.location) continue;

        const lng = stat.location[0];
        const lat = stat.location[1];

        let pm25 = 0;

        const pm25Entry = stat.mtDataList.find(v => v.mtName === 'PM25');

        if (!pm25Entry) continue;
        pm25 = pm25Entry.value;

        const iconUrl = stat.tags.includes('EPA')
          ? epaUrl(this.mMap.get(stat._id).desc, pm25)
          : getIconUrl(pm25);

        let infoText = stat.code
          ? `<strong>${stat.shortCode}/${stat.code}</strong>`
          : `<strong>${this.mMap.get(stat._id).desc}</strong>`;
        let title = stat.code
          ? `${stat.code}`
          : `${this.mMap.get(stat._id).desc}`;

        ret.push({
          title,
          position: { lat, lng },
          pm25,
          infoText,
          iconUrl,
        });
      }

      return ret;
    },
  },
};
</script>

<style></style>
