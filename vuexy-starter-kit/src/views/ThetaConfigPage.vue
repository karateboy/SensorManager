<template>
  <b-form @submit.prevent @change="onChange">
    <b-row>
      <b-col cols="12">
        <b-form-group label="型號" label-for="model" label-cols-md="3">
          <v-select
            v-model="paramObj.model"
            :options="models"
            @input="onChange"
          />
        </b-form-group>
      </b-col>
    </b-row>
  </b-form>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script>
import Vue from 'vue';
import { mapState, mapGetters } from 'vuex';
import vSelect from 'vue-select';
import axios from 'axios';

export default Vue.extend({
  components: {
    vSelect,
  },
  props: {
    paramStr: {
      type: String,
      default: ``,
    },
  },
  data() {
    let paramObj = {
      model: 'T100',
    };

    if (this.paramStr) {
      paramObj = JSON.parse(this.paramStr);
    } else {
      this.$emit('param-changed', JSON.stringify(this.paramObj));
    }

    return {
      paramObj,
      models: ['T100', 'T200'],
    };
  },
  computed: {},
  mounted() {},
  methods: {
    justify() {
      const param = this.paramObj;
    },
    onChange(evt) {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.paramObj));
    },
  },
});
</script>
