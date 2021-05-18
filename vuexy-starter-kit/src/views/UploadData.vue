<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col>
            <b-form-file
              v-model="form.uploadFile"
              :state="Boolean(form.uploadFile)"
              accept=".csv"
              browse-text="..."
              placeholder="選擇上傳檔案..."
              drop-placeholder="拖曳檔案至此..."
            ></b-form-file>
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
              :disabled="!Boolean(form.uploadFile)"
              @click="upload"
            >
              上傳
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
import Ripple from 'vue-ripple-directive';
import axios from 'axios';

export default Vue.extend({
  directives: {
    Ripple,
  },
  data() {
    return {
      form: {
        uploadFile: undefined,
      },
    };
  },
  computed: {},
  methods: {
    async recalculate() {
      const monitors = this.form.monitors.join(':');
      const url = `/Recalculate/${monitors}/${this.form.range[0]}/${this.form.range[1]}`;

      const ret = await axios.get(url);
      if (ret.data.ok) {
        this.$bvModal.msgBoxOk('成功');
      }
    },
    upload() {
      let file = this.form.uploadFile;

      var formData = new FormData();
      formData.append('data', file);

      axios
        .post('/sensorData', formData, {
          headers: {
            'Content-Type': 'multipart/form-data',
          },
        })
        .then(res => {
          if (res.status === 200) {
            this.$bvModal.msgBoxOk('上傳成功', { headerBgVariant: 'info' });
          } else {
            this.$bvModal.msgBoxOk(
              `上傳失敗 ${res.status} - ${res.statusText}`,
              {
                headerBgVariant: 'danger',
              },
            );
          }
        })
        .catch(err => alert(err));
    },
  },
});
</script>

<style></style>
