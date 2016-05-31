import React, { Component, PropTypes } from 'react';
import ReactGridLayout from 'react-grid-layout';
import DashboardVisualisationList from './DashboardVisualisationList';
import DashboardCanvasItem from './DashboardCanvasItem';

require('../../styles/DashboardEditor.scss');
require('../../../node_modules/react-grid-layout/css/styles.css');
require('../../../node_modules/react-resizable/css/styles.css');

const getArrayFromObject = object => Object.keys(object).map(key => object[key]);

const getNewEntityId = (entities, itemType) => {
  const entityArray = getArrayFromObject(entities);
  let highestIdInt = 0;

  entityArray.forEach(item => {
    if (item.type === itemType) {
      const idInt = parseInt(item.id.substring(itemType.length + 1), 10);
      if (idInt > highestIdInt) highestIdInt = idInt;
    }
  });

  const newIdInt = highestIdInt + 1;

  return `${itemType}-${newIdInt}`;
};

const getFirstBlankRowGroup = (layout, height) => {
  /* Function to find the first collection of blank rows big enough for the
  /* default height of the entity about to be inserted. */

  /* If layout is empty, return the first row */
  if (layout.length === 0) return 0;

  const occupiedRows = {};
  let lastRow = 0;

  /* Build an object of all occupied rows, and record the last currently
  /* occupied row. */
  layout.forEach(item => {
    for (let row = item.y; row < (item.y + item.h); row++) {
      occupiedRows[row] = true;
      if (row > lastRow) lastRow = row;
    }
  });

  /* Loop through every row from 0 to the last occupied. If we encounter a blank
  /* row i, check the next sequential rows until we have enough blank rows to
  /* fit our height. If we do, return row i. */
  for (let i = 0; i < lastRow; i++) {
    if (!occupiedRows[i]) {
      let haveSpace = true;

      for (let y = i + 1; y < (i + height); y++) {
        if (occupiedRows[y]) {
          haveSpace = false;
        }
      }

      if (haveSpace) {
        return i;
      }
    }
  }

  /* Otherwise, just return the row after the last currently occupied row. */
  return lastRow + 1;
};

export default class DashboardEditor extends Component {

  constructor() {
    super();
    this.state = {
      type: 'dashboard',
      name: 'Untitled dashboard',
      entities: {},
      propLayout: [],
      currentLayout: [],
      gridWidth: 1024,
    };
    this.handleLayoutChange = this.handleLayoutChange.bind(this);
    this.handleEntityToggle = this.handleEntityToggle.bind(this);
    this.handleResize = this.handleResize.bind(this);
    this.handleEntityUpdate = this.handleEntityUpdate.bind(this);
  }

  componentDidMount() {
    this.handleResize();
    window.addEventListener('resize', this.handleResize);
  }

  componentWillUnmount() {
    window.removeEventListener('resize', this.handleResize);
  }

  handleResize() {
    // Offset the padding width (16px on each side)
    const newWidth = this.refs.DashboardEditorCanvasContainer.clientWidth - 32;
    if (newWidth !== this.state.gridWidth) {
      this.setState({
        gridWidth: newWidth,
      });
    }
  }

  handleLayoutChange(layout) {
    /* When the layout is updated (e.g by an entity being resized on the
    /* canvas), store a copy of the updated layout. We use this copy as the
    /* starting point for building a new layout whenever we add or remove an
    /* entity from the canvas. We then pass that new layout as a prop to the
    /* ReactGridLayout component. Trying to store the prop layout and the
    /* current layout as the same value doesn't work - the ReactGridLayout
    /* component updates its internal state on these kinds of layout changes,
    /* and updating its "layout" prop with the updated layout here leads to
    /* weird race conditions and broken layouts. */
    this.setState({ currentLayout: layout });
  }

  handleEntityToggle(item, itemType) {
    const newEntities = this.state.entities;
    const newLayout = this.state.currentLayout;

    if (this.state.entities[item.id]) {
      delete newEntities[item.id];
      this.setState({
        entities: newEntities,
      });
    } else {
      if (itemType === 'visualisation') {
        this.props.onAddVisualisation(item.datasetId);
        newEntities[item.id] = {
          type: itemType,
          id: item.id,
          visualisation: item,
        };

        newLayout.push({
          w: 6,
          h: 4,
          minW: 4,
          minH: 4,
          x: 0,
          y: getFirstBlankRowGroup(this.state.currentLayout, 4),
          i: item.id,
        });
      } else if (itemType === 'text') {
        const newEntityId = getNewEntityId(this.state.entities, itemType);

        newEntities[newEntityId] = {
          type: itemType,
          id: newEntityId,
          content: '',
        };
        newLayout.push({
          w: 4,
          minW: 2,
          h: 1,
          x: 0,
          y: getFirstBlankRowGroup(this.state.currentLayout, 1),
          i: newEntityId,
        });
      }

      /* Note that we update the propLayout, not the currentLayout, to prevent
      /* race conditions. */
      this.setState({
        propLayout: newLayout,
        entities: newEntities,
      });
    }
  }

  handleEntityUpdate(entity) {
    const newEntities = this.state.entities;

    newEntities[entity.id] = entity;
    this.setState({ entities: newEntities });
  }

  render() {
    const canvasWidth = this.state.gridWidth;
    const rowHeight = canvasWidth / 12;

    return (
      <div className="DashboardEditor">
        <DashboardVisualisationList
          visualisations={getArrayFromObject(this.props.visualisations)}
          onEntityClick={this.handleEntityToggle}
          dashboardItems={this.state.entities}
        />
        <div
          className="DashboardEditorCanvasContainer"
          ref="DashboardEditorCanvasContainer"
        >
          <div className="DashboardEditorCanvasControls">
            <button
              className="clickable"
              onClick={() => this.handleEntityToggle({ content: '' }, 'text')}
            >
              Add new text element
            </button>
          </div>
          <div
            className="DashboardEditorCanvas"
            style={{
              position: 'relative',
              boxSizing: 'initial',
              padding: '16px',
            }}
          >
            <ReactGridLayout
              className="layout"
              cols={12}
              rowHeight={rowHeight}
              width={canvasWidth}
              verticalCompact={false}
              layout={this.state.propLayout}
              onLayoutChange={this.handleLayoutChange}

              /* Setting any margin results in grid units being different
              /* vertically and horizontally due to implementation details. Use
              /* a margin on the grid item themselves for now. */
              margin={[0, 0]}
            >
              {getArrayFromObject(this.state.entities).map(item =>
                <div
                  key={item.id}
                >
                  <DashboardCanvasItem
                    item={item}
                    datasets={this.props.datasets}
                    canvasLayout={this.state.currentLayout}
                    canvasWidth={canvasWidth}
                    onDeleteClick={this.handleEntityToggle}
                    onEntityUpdate={this.handleEntityUpdate}
                  />
                </div>
              )}
            </ReactGridLayout>
          </div>
        </div>
      </div>
    );
  }
}

DashboardEditor.propTypes = {
  visualisations: PropTypes.object.isRequired,
  datasets: PropTypes.object.isRequired,
  onAddVisualisation: PropTypes.func.isRequired,
};