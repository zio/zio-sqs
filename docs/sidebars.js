const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO SQS",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        "getting-started",
        {
          type: "category",
          label: "Reference",
          collapsed: false,
          items: [
            "reference"
          ]
        },
        {
          type: "category",
          label: "How-to Guides",
          collapsed: false,
          items: [
            "consuming",
            "producing",
            "serialization",
            "testing-locally"
          ]
        }
      ]
    }
  ]
};

module.exports = sidebars;
