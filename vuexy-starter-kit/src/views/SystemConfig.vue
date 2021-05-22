<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="感測器顯示位置:"
              label-for="sensorGPS"
              label-size="lg"
              label-class="font-weight-bold pt-0"
            >
              <b-form-checkbox id="sensorGPS" v-model="form.sensorGPS"
                >使用感測器GPS回報位置</b-form-checkbox
              >
            </b-form-group>
          </b-col>
        </b-row>
        <br />
        <b-row>
          <b-col offset-md="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="setSensorGpsSetting()"
            >
              儲存
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import axios from 'axios';

export default Vue.extend({
  directives: {
    Ripple,
  },
  data() {
    return {
      form: {
        sensorGPS: true,
      },
    };
  },
  mounted() {
    this.getSensorGpsSetting();
  },
  methods: {
    async getSensorGpsSetting() {
      const res = await axios.get('/SystemConfig/SensorGPS');
      this.form.sensorGPS = res.data.value;
    },
    async setSensorGpsSetting() {
      const res = await axios.post('/SystemConfig/SensorGPS', {
        value: this.form.sensorGPS,
      });
      if (res.status === 200) {
        this.$bvModal.msgBoxOk('成功', { headerBgVariant: 'info' });
      } else {
        this.$bvModal.msgBoxOk(`失敗 ${res.status} - ${res.statusText}`, {
          headerBgVariant: 'danger',
        });
      }
    },
  },
});
</script>

<style></style>
